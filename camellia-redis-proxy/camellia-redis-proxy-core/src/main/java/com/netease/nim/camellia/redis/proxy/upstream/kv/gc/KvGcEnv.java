package com.netease.nim.camellia.redis.proxy.upstream.kv.gc;

import com.netease.nim.camellia.redis.base.utils.SafeEncoder;
import com.netease.nim.camellia.redis.proxy.cluster.ProxyNode;
import com.netease.nim.camellia.redis.proxy.command.Command;
import com.netease.nim.camellia.redis.proxy.command.ProxyCurrentNodeInfo;
import com.netease.nim.camellia.redis.proxy.conf.ProxyDynamicConf;
import com.netease.nim.camellia.redis.proxy.enums.RedisCommand;
import com.netease.nim.camellia.redis.proxy.enums.RedisKeyword;
import com.netease.nim.camellia.redis.proxy.netty.GlobalRedisProxyEnv;
import com.netease.nim.camellia.redis.proxy.reply.BulkReply;
import com.netease.nim.camellia.redis.proxy.reply.ErrorReply;
import com.netease.nim.camellia.redis.proxy.reply.Reply;
import com.netease.nim.camellia.redis.proxy.upstream.IUpstreamClient;
import com.netease.nim.camellia.redis.proxy.upstream.kv.utils.BytesUtils;
import com.netease.nim.camellia.redis.proxy.util.Utils;
import com.netease.nim.camellia.tools.executor.CamelliaThreadFactory;
import com.netease.nim.camellia.tools.utils.CamelliaMapUtils;
import com.netease.nim.camellia.tools.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by caojiajun on 2024/8/15
 */
public class KvGcEnv {

    private static final Logger logger = LoggerFactory.getLogger(KvGcEnv.class);

    private static final ConcurrentHashMap<String, ScheduledFuture<?>> lockMap = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new CamelliaThreadFactory("kv-gc-lock"));
    private static final ReentrantLock lock = new ReentrantLock();
    private static final ConcurrentHashMap<String, Long> lastGcTimeMap = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Pair<byte[], Integer>> metaKeyStartKeyMap = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Map<Integer, Pair<byte[], Integer>>> subKeyStartKeyMap = new ConcurrentHashMap<>();

    private static boolean redisEnable() {
        return redisUrl() != null && redisKey() != null;
    }

    private static String redisUrl() {
        return ProxyDynamicConf.getString("kv.gc.lock.redis.url", null);
    }

    private static String redisKey() {
        return ProxyDynamicConf.getString("kv.gc.lock.redis.key", null);
    }

    public static boolean acquireGcLock(String namespace) {
        lock.lock();
        try {
            ScheduledFuture<?> future = lockMap.get(namespace);
            if (future != null) {
                return true;
            }
            if (!redisEnable()) {
                return true;
            }

            String redisKey = redisKey() + "#" + namespace + "#lock";
            boolean success = lock(redisKey);
            if (success) {
                future = scheduler.scheduleAtFixedRate(() -> lock(redisKey), 5, 5, TimeUnit.SECONDS);
                lockMap.put(namespace, future);
            }
            return success;
        } finally {
            lock.unlock();
        }
    }

    public static void release(String namespace) {
        lock.lock();
        try {
            ScheduledFuture<?> future = lockMap.remove(namespace);
            if (future != null) {
                future.cancel(false);
            }
        } finally {
            lock.unlock();
        }
    }

    public static void updateGcTime(String namespace, long gcTime) {
        try {
            lastGcTimeMap.put(namespace, gcTime);
            if (redisEnable()) {
                String key = redisKey() + "#" + namespace + "#gcTime";
                setKv(key, Utils.stringToBytes(String.valueOf(gcTime)));
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    public static long getLastGcTime(String namespace) {
        try {
            if (redisEnable()) {
                String key = redisKey() + "#" + namespace + "#gcTime";
                byte[] data = getK(key);
                if (data != null) {
                    return Long.parseLong(Utils.bytesToString(data));
                }
            }
            Long lastGcTime = lastGcTimeMap.get(namespace);
            return lastGcTime == null ? 0L : lastGcTime;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            Long lastGcTime = lastGcTimeMap.get(namespace);
            return lastGcTime == null ? 0L : lastGcTime;
        }
    }

    public static Pair<byte[], Integer> getMetaKeyScanStartKey(String namespace) {
        try {
            if (redisEnable()) {
                String key = redisKey() + "#" + namespace + "#metKeyScanStartKey";
                return toPair(getK(key));
            }
            return metaKeyStartKeyMap.get(namespace);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return metaKeyStartKeyMap.get(namespace);
        }
    }

    public static void updateMetaKeyScanStartKey(String namespace, byte[] startKey, int slot) {
        try {
            if (startKey == null) {
                metaKeyStartKeyMap.remove(namespace);
            } else {
                metaKeyStartKeyMap.put(namespace, new Pair<>(startKey, slot));
            }
            if (redisEnable()) {
                String key = redisKey() + "#" + namespace + "#metKeyScanStartKey";
                if (startKey == null) {
                    delK(key);
                } else {
                    setKv(key, toBytes(new Pair<>(startKey, slot)));
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    public static Pair<byte[], Integer> getSubKeyScanStartKey(String namespace, int index) {
        try {
            if (redisEnable()) {
                String key = redisKey() + "#" + namespace + "#subKeyScanStartKey#" + index;
                return toPair(getK(key));
            }
            Map<Integer, Pair<byte[], Integer>> subMap = CamelliaMapUtils.computeIfAbsent(subKeyStartKeyMap, namespace, k -> new ConcurrentHashMap<>());
            return subMap.get(index);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            Map<Integer, Pair<byte[], Integer>> subMap = CamelliaMapUtils.computeIfAbsent(subKeyStartKeyMap, namespace, k -> new ConcurrentHashMap<>());
            return subMap.get(index);
        }
    }

    public static void setSubKeyScanStartKey(String namespace, int index, byte[] startKey, int slot) {
        try {
            Map<Integer, Pair<byte[], Integer>> subMap = CamelliaMapUtils.computeIfAbsent(subKeyStartKeyMap, namespace, k -> new ConcurrentHashMap<>());
            if (startKey == null) {
                subMap.remove(index);
            } else {
                subMap.put(index, new Pair<>(startKey, slot));
            }
            if (redisEnable()) {
                String key = redisKey() + "#" + namespace + "#subKey#" + index;
                if (startKey == null) {
                    delK(key);
                } else {
                    setKv(key, toBytes(new Pair<>(startKey, slot)));
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    private static final byte[] SCRIPT = SafeEncoder.encode("if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('expire', KEYS[1], ARGV[2]) else return 0 end");

    private static boolean lock(String redisKey) {
        try {
            ProxyNode current = ProxyCurrentNodeInfo.current();
            byte[][] args1 = new byte[][] {RedisCommand.SET.raw(), Utils.stringToBytes(redisKey), Utils.stringToBytes(current.toString()),
                    RedisKeyword.NX.getRaw(), RedisKeyword.EX.getRaw(), Utils.stringToBytes(String.valueOf(60))};
            Command command1 = new Command(args1);
            byte[][] args2 = new byte[][] {RedisCommand.GET.raw(), Utils.stringToBytes(redisKey)};
            Command command2 = new Command(args2);
            List<Command> commands = new ArrayList<>(2);
            commands.add(command1);
            commands.add(command2);
            List<Reply> replies = sendCommands(commands);
            Reply reply = replies.get(1);
            if (reply instanceof ErrorReply) {
                return false;
            }
            if (reply instanceof BulkReply) {
                ProxyNode lockedNode = ProxyNode.parseString(Utils.bytesToString(((BulkReply) reply).getRaw()));
                if (lockedNode == null) {
                    return false;
                }
                if (lockedNode.equals(current)) {
                    byte[][] args3 = new byte[][]{RedisCommand.EVAL.raw(), SCRIPT, Utils.stringToBytes("1"), Utils.stringToBytes(redisKey),
                            Utils.stringToBytes(current.toString()), Utils.stringToBytes(String.valueOf(60))};
                    Command command3 = new Command(args3);
                    sendCommand(command3);
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static void setKv(String key, byte[] value) throws Exception {
        byte[][] cmd = new byte[4][];
        cmd[0] = RedisCommand.SETEX.raw();
        cmd[1] = Utils.stringToBytes(key);
        cmd[2] = Utils.stringToBytes(String.valueOf(ProxyDynamicConf.getInt("kv.gc.env.redis.cache.ttl.seconds", 3600*24*7)));
        cmd[3] = value;
        sendCommand(new Command(cmd));
    }

    private static byte[] getK(String key) throws Exception {
        byte[][] cmd = new byte[2][];
        cmd[0] = RedisCommand.GET.raw();
        cmd[1] = Utils.stringToBytes(key);
        Reply reply = sendCommand(new Command(cmd));
        if (reply instanceof BulkReply) {
            return ((BulkReply) reply).getRaw();
        }
        return null;
    }

    private static void delK(String key) throws Exception {
        byte[][] cmd = new byte[2][];
        cmd[0] = RedisCommand.DEL.raw();
        cmd[1] = Utils.stringToBytes(key);
        sendCommand(new Command(cmd));
    }

    private static List<Reply> sendCommands(List<Command> commands) throws Exception {
        List<CompletableFuture<Reply>> futures = new ArrayList<>(commands.size());
        for (int i = 0; i < commands.size(); i++) {
            futures.add(new CompletableFuture<>());
        }
        IUpstreamClient client = GlobalRedisProxyEnv.getRedisProxyEnv().getClientFactory().get(redisUrl());
        client.sendCommand(-1, commands, futures);
        List<Reply> replies = new ArrayList<>(commands.size());
        for (CompletableFuture<Reply> future : futures) {
            replies.add(future.get(10000, TimeUnit.MILLISECONDS));
        }
        return replies;
    }

    private static Reply sendCommand(Command command) throws Exception {
        CompletableFuture<Reply> future = new CompletableFuture<>();
        IUpstreamClient client = GlobalRedisProxyEnv.getRedisProxyEnv().getClientFactory().get(redisUrl());
        client.sendCommand(-1, Collections.singletonList(command), Collections.singletonList(future));
        return future.get(10000, TimeUnit.MILLISECONDS);
    }

    private static Pair<byte[], Integer> toPair(byte[] data) {
        if (data == null) {
            return null;
        }
        int slot = BytesUtils.toInt(data);
        byte[] bytes = new byte[data.length - 4];
        System.arraycopy(data, 4, bytes, 0, bytes.length);
        return new Pair<>(bytes, slot);
    }

    private static byte[] toBytes(Pair<byte[], Integer> pair) {
        byte[] data = new byte[pair.getFirst().length + 4];
        byte[] slot = BytesUtils.toBytes(pair.getSecond());
        System.arraycopy(slot, 0, data, 0, slot.length);
        System.arraycopy(pair.getFirst(), 0, data, 4, pair.getFirst().length);
        return data;
    }
}
