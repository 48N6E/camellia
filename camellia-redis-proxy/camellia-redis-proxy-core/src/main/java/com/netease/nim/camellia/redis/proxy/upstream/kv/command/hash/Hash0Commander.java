package com.netease.nim.camellia.redis.proxy.upstream.kv.command.hash;

import com.netease.nim.camellia.redis.proxy.command.Command;
import com.netease.nim.camellia.redis.proxy.enums.RedisCommand;
import com.netease.nim.camellia.redis.proxy.reply.ErrorReply;
import com.netease.nim.camellia.redis.proxy.reply.Reply;
import com.netease.nim.camellia.redis.proxy.upstream.kv.cache.RedisHash;
import com.netease.nim.camellia.redis.proxy.upstream.kv.command.Commander;
import com.netease.nim.camellia.redis.proxy.upstream.kv.command.CommanderConfig;
import com.netease.nim.camellia.redis.proxy.upstream.kv.kv.KeyValue;
import com.netease.nim.camellia.redis.proxy.upstream.kv.kv.Sort;
import com.netease.nim.camellia.redis.proxy.upstream.kv.meta.KeyMeta;
import com.netease.nim.camellia.redis.proxy.util.Utils;
import com.netease.nim.camellia.tools.utils.BytesKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by caojiajun on 2024/5/15
 */
public abstract class Hash0Commander extends Commander {

    public Hash0Commander(CommanderConfig commanderConfig) {
        super(commanderConfig);
    }

    protected final RedisHash loadLRUCache(KeyMeta keyMeta, byte[] key) {
        Map<BytesKey, byte[]> map = hgetallFromKv(keyMeta, key);
        return new RedisHash(map);
    }

    protected final Map<BytesKey, byte[]> hgetallFromKv(KeyMeta keyMeta, byte[] key) {
        Map<BytesKey, byte[]> map = new HashMap<>();
        byte[] startKey = keyDesign.hashFieldSubKey(keyMeta, key, new byte[0]);
        byte[] prefix = startKey;
        int limit = kvConfig.scanBatch();
        int hashMaxSize = kvConfig.hashMaxSize();
        while (true) {
            List<KeyValue> scan = kvClient.scanByPrefix(startKey, prefix, limit, Sort.ASC, false);
            if (scan.isEmpty()) {
                break;
            }
            for (KeyValue keyValue : scan) {
                byte[] field = keyDesign.decodeHashFieldBySubKey(keyValue.getKey(), key);
                map.put(new BytesKey(field), keyValue.getValue());
                startKey = keyValue.getKey();
                if (map.size() >= hashMaxSize) {
                    break;
                }
            }
            if (scan.size() < limit) {
                break;
            }
        }
        return map;
    }

    protected final ErrorReply buildCache(byte[] cacheKey, Map<BytesKey, byte[]> map) {
        if (map.isEmpty()) {
            return null;
        }
        //build cache
        byte[][] args1 = new byte[map.size() * 2 + 2][];
        args1[0] = RedisCommand.HSET.raw();
        args1[1] = cacheKey;
        int i = 0;
        for (Map.Entry<BytesKey, byte[]> entry : map.entrySet()) {
            args1[i + 2] = entry.getKey().getKey();
            args1[i + 3] = entry.getValue();
            i += 2;
        }
        Command hsetCmd = new Command(args1);
        Command pexpireCmd = new Command(new byte[][]{RedisCommand.PEXPIRE.raw(), cacheKey, hgetallCacheMillis()});
        List<Command> commands = new ArrayList<>(2);
        commands.add(hsetCmd);
        commands.add(pexpireCmd);
        List<Reply> replyList = sync(cacheRedisTemplate.sendCommand(commands));
        for (Reply reply : replyList) {
            if (reply instanceof ErrorReply) {
                return (ErrorReply) reply;
            }
        }
        return null;
    }

    protected byte[] hgetCacheMillis() {
        return Utils.stringToBytes(String.valueOf(cacheConfig.hgetCacheMillis()));
    }

    protected final byte[] hgetallCacheMillis() {
        return Utils.stringToBytes(String.valueOf(cacheConfig.hgetallCacheMillis()));
    }
}
