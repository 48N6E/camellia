package com.netease.nim.camellia.redis.proxy.sentinel;

import com.netease.nim.camellia.redis.proxy.auth.HelloCommandUtil;
import com.netease.nim.camellia.redis.proxy.cluster.ProxyNode;
import com.netease.nim.camellia.redis.proxy.command.Command;
import com.netease.nim.camellia.redis.proxy.conf.ProxyDynamicConf;
import com.netease.nim.camellia.redis.proxy.enums.RedisCommand;
import com.netease.nim.camellia.redis.proxy.monitor.ChannelMonitor;
import com.netease.nim.camellia.redis.proxy.netty.ChannelInfo;
import com.netease.nim.camellia.redis.proxy.netty.GlobalRedisProxyEnv;
import com.netease.nim.camellia.redis.proxy.reply.*;
import com.netease.nim.camellia.redis.proxy.upstream.connection.RedisConnection;
import com.netease.nim.camellia.redis.proxy.upstream.connection.RedisConnectionHub;
import com.netease.nim.camellia.redis.proxy.upstream.sentinel.RedisSentinelUtils;
import com.netease.nim.camellia.redis.proxy.util.Utils;
import com.netease.nim.camellia.tools.executor.CamelliaThreadFactory;
import com.netease.nim.camellia.tools.utils.CamelliaMapUtils;
import com.netease.nim.camellia.tools.utils.InetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.*;

/**
 * 把多台proxy伪装成redis-sentinel集群
 * 当一台proxy节点挂了，会通知客户端连接其他proxy节点
 * Created by caojiajun on 2023/12/26
 */
public class ProxySentinelModeProcessor {

    private static final Logger logger = LoggerFactory.getLogger(ProxySentinelModeProcessor.class);

    private static final ErrorReply UNKNOWN_MASTER_NAME = new ErrorReply("ERR sentinel unknown master name");
    private static final ErrorReply SENTINEL_MODE_NOT_AVAILABLE = new ErrorReply("ERR sentinel mode not available");
    private static final ErrorReply SENTINEL_MODE_NOT_ONLINE = new ErrorReply("ERR sentinel mode not online");
    private static final String heartbeat = "heartbeat";

    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, new CamelliaThreadFactory("proxy-sentinel-mode-schedule"));

    private String masterName;
    private ProxyNode currentNode;
    private List<ProxyNode> onlineNodes;
    private List<ProxyNode> allNodes;
    private String sentinelUserName;
    private String sentinelPassword;
    private final ConcurrentHashMap<String, Connection> connectionMap = new ConcurrentHashMap<>();
    private boolean init = false;

    public ProxySentinelModeProcessor() {
        GlobalRedisProxyEnv.addAfterStartCallback(this::init);
    }

    private synchronized void init() {
        if (init) return;
        //current node
        ProxyNode currentNode = new ProxyNode();
        String host = ProxyDynamicConf.getString("proxy.sentinel.mode.current.node.host", null);
        if (host == null) {
            InetAddress inetAddress = InetUtils.findFirstNonLoopbackAddress();
            if (inetAddress == null) {
                throw new IllegalStateException("not found non loopback address");
            }
            currentNode.setHost(inetAddress.getHostAddress());
        } else {
            currentNode.setHost(host);
        }
        this.sentinelUserName = ProxyDynamicConf.getString("proxy.sentinel.mode.sentinel.username", null);
        this.sentinelPassword = ProxyDynamicConf.getString("proxy.sentinel.mode.sentinel.password", null);
        int port = GlobalRedisProxyEnv.getPort();
        int cport = GlobalRedisProxyEnv.getCport();
        if (port == 0 || cport == 0) {
            throw new IllegalStateException("redis proxy not start");
        }
        currentNode.setPort(port);
        currentNode.setCport(cport);
        this.currentNode = currentNode;
        //online nodes
        boolean success = reloadNodes();
        if (!success) {
            throw new IllegalArgumentException("illegal 'proxy.sentinel.mode.nodes' in ProxyDynamicConf");
        }
        List<ProxyNode> onlineNodes = new ArrayList<>();
        if (SentinelModeStatus.getStatus() == SentinelModeStatus.Status.ONLINE) {
            onlineNodes.add(currentNode);
        }
        for (ProxyNode node : allNodes) {
            if (node.equals(currentNode)) continue;
            boolean online = heartbeat(node);
            if (online) {
                onlineNodes.add(node);
            }
        }
        Collections.sort(onlineNodes);
        this.onlineNodes = onlineNodes;
        this.masterName = ProxyDynamicConf.getString("proxy.sentinel.mode.master.name", "camellia_sentinel");
        logger.info("sentinel mode init success, masterName = {}, currentNode = {}, onlineNodes = {}, allNodes = {}",
                this.masterName, this.currentNode, this.onlineNodes, this.allNodes);
        int intervalSeconds = ProxyDynamicConf.getInt("proxy.sentinel.mode.heartbeat.interval.seconds", 5);
        scheduler.scheduleAtFixedRate(this::schedule, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        logger.info("sentinel mode heartbeat schedule start success, intervalSeconds = {}", intervalSeconds);
        init = true;
    }

    /**
     * sentinelCommands
     * @param command command
     * @return Reply with CompletableFuture
     */
    public CompletableFuture<Reply> sentinelCommands(Command command) {
        RedisCommand redisCommand = command.getRedisCommand();
        ChannelInfo channelInfo = command.getChannelInfo();
        Connection connection = getConnection(channelInfo.getConsid());
        connection.channelInfo = channelInfo;
        byte[][] args = command.getObjects();
        if (redisCommand == RedisCommand.AUTH) {
            Reply reply = auth(connection, command);
            return wrapper(connection, redisCommand, reply);
        }
        if (redisCommand == RedisCommand.HELLO) {
            Reply reply = hello(connection, command);
            return wrapper(connection, redisCommand, reply);
        }
        //ping, skip auth
        if (redisCommand == RedisCommand.PING) {
            Reply reply;
            if (connection.subscribe) {
                Reply[] replies = new Reply[1];
                replies[0] = new BulkReply(Utils.stringToBytes(StatusReply.PONG.getStatus()));
                reply = new MultiBulkReply(replies);
            } else {
                reply = StatusReply.PONG;
            }
            return wrapper(connection, redisCommand, reply);
        }
        if (redisCommand == RedisCommand.SENTINEL) {
            if (args.length < 2) {
                return wrapper(connection, redisCommand, ErrorReply.argNumWrong(redisCommand));
            }
            String param = Utils.bytesToString(args[1]);
            //heartbeat, skip auth
            if (param.equalsIgnoreCase(heartbeat)) {
                if (SentinelModeStatus.getStatus() == SentinelModeStatus.Status.ONLINE) {
                    return wrapper(connection, redisCommand, StatusReply.OK);
                } else {
                    return wrapper(connection, redisCommand, SENTINEL_MODE_NOT_ONLINE);
                }
            }
            //check auth
            if (requirePassword() && !connection.auth) {
                return wrapper(connection, redisCommand, ErrorReply.NO_AUTH);
            }
            //get master addr by name
            if (!param.equalsIgnoreCase(Utils.bytesToString(RedisSentinelUtils.SENTINEL_GET_MASTER_ADDR_BY_NAME))) {
                return wrapper(connection, redisCommand, ErrorReply.NOT_SUPPORT);
            }
            if (args.length < 3) {
                return wrapper(connection, redisCommand, ErrorReply.argNumWrong(redisCommand));
            }
            String masterName = Utils.bytesToString(args[2]);
            if (!masterName.equals(this.masterName)) {
                return wrapper(connection, redisCommand, UNKNOWN_MASTER_NAME);
            }
            ProxyNode target = selectOnlineNode(channelInfo);
            Reply reply;
            if (target == null) {
                reply = SENTINEL_MODE_NOT_AVAILABLE;
            } else {
                Reply[] replies = new Reply[2];
                replies[0] = new BulkReply(Utils.stringToBytes(target.getHost()));
                replies[1] = new BulkReply(Utils.stringToBytes(String.valueOf(target.getPort())));
                if (channelInfo.getConsid() != null) {
                    connection.proxyNode = target;
                }
                reply = new MultiBulkReply(replies);
            }
            return wrapper(connection, redisCommand, reply);
        }
        //check auth
        if (requirePassword() && !connection.auth) {
            return wrapper(connection, redisCommand, ErrorReply.NO_AUTH);
        }
        //subscribe
        if (redisCommand == RedisCommand.SUBSCRIBE) {
            if (args.length < 2) {
                return wrapper(connection, redisCommand, ErrorReply.argNumWrong(redisCommand));
            }
            String param = Utils.bytesToString(args[1]);
            //only support +switch-master
            if (!param.equalsIgnoreCase(Utils.bytesToString(RedisSentinelUtils.MASTER_SWITCH))) {
                return wrapper(connection, redisCommand, ErrorReply.NOT_SUPPORT);
            }
            Reply[] replies = new Reply[3];
            replies[0] = new BulkReply(Utils.stringToBytes("subscribe"));
            replies[1] = new BulkReply(Utils.stringToBytes("+switch-master"));
            replies[2] = new IntegerReply(1L);
            CompletableFuture<Reply> future = wrapper(connection, redisCommand, new MultiBulkReply(replies));
            //set subscribe to true after wrapper
            connection.subscribe = true;
            return future;
        }
        //other command not support
        return wrapper(connection, redisCommand, ErrorReply.NOT_SUPPORT);
    }

    private Reply auth(Connection connection, Command command) {
        if (!requirePassword()) {
            return new ErrorReply("ERR Client sent AUTH, but no password is set");
        }
        byte[][] objects = command.getObjects();
        if (objects.length != 2 && objects.length != 3) {
            return ErrorReply.INVALID_PASSWORD;
        }
        String userName = null;
        String password;
        if (objects.length == 2) {
            password = Utils.bytesToString(objects[1]);
        } else {
            userName = Utils.bytesToString(objects[1]);
            password = Utils.bytesToString(objects[2]);
        }
        if (checkPassword(userName, password)) {
            connection.auth = true;
            return StatusReply.OK;
        }
        return ErrorReply.INVALID_PASSWORD;
    }

    private Reply hello(Connection connection, Command command) {
        byte[][] objects = command.getObjects();
        if (objects.length == 1) {
            return HelloCommandUtil.helloCmdReply();
        }
        if (objects.length > 2) {
            for (int i=1; i<objects.length; i++) {
                String param = Utils.bytesToString(objects[i]);
                if (param.equalsIgnoreCase("AUTH")) {
                    if (!requirePassword()) {
                        return new ErrorReply("ERR Client sent AUTH, but no password is set");
                    }
                    String userName;
                    String password;
                    try {
                        userName = Utils.bytesToString(objects[i + 1]);
                        password = Utils.bytesToString(objects[i + 2]);
                    } catch (Exception e) {
                        return HelloCommandUtil.SETNAME_SYNTAX_ERROR;
                    }
                    if (checkPassword(userName, password)) {
                        connection.auth = true;
                        return HelloCommandUtil.helloCmdReply();
                    }
                    return ErrorReply.WRONG_PASS;
                }
            }
        }
        return HelloCommandUtil.helloCmdReply();
    }

    private boolean requirePassword() {
        return sentinelPassword != null;
    }

    private boolean checkPassword(String userName, String password) {
        if (sentinelUserName == null && sentinelPassword.equals(password)) {
            return true;
        }
        if (sentinelUserName != null && sentinelUserName.equals(userName) && sentinelPassword.equals(password)) {
            return true;
        }
        return false;
    }

    private CompletableFuture<Reply> wrapper(Connection connection, RedisCommand redisCommand, Reply reply) {
        if (connection.subscribe) {
            connection.channelInfo.getCommandTaskQueue().reply(redisCommand, reply, false, false);
            return null;
        } else {
            CompletableFuture<Reply> future = new CompletableFuture<>();
            future.complete(reply);
            return future;
        }
    }

    /**
     * 获取当前节点
     * @return 当前节点
     */
    public ProxyNode getCurrentNode() {
        return currentNode;
    }

    /**
     * 获取在线节点列表
     * @return 节点列表
     */
    public List<ProxyNode> getOnlineNodes() {
        return new ArrayList<>(onlineNodes);
    }

    private void schedule() {
        try {
            //reload nodes
            reloadNodes();
            //clear inactive client
            Set<String> set = new HashSet<>(connectionMap.keySet());
            for (String consid : set) {
                Connection connection = connectionMap.get(consid);
                if (connection == null) {
                    continue;
                }
                ChannelInfo info = ChannelMonitor.getChannel(consid);
                if (info == null) {
                    if (connection.channelInfo == null) {
                        connectionMap.remove(consid);
                        continue;
                    }
                    boolean active = connection.channelInfo.getCtx().channel().isActive();
                    if (!active) {
                        connectionMap.remove(consid);
                    }
                    continue;
                }
                if (connection.channelInfo == null) {
                    continue;
                }
                boolean active = connection.channelInfo.getCtx().channel().isActive();
                if (!active) {
                    connectionMap.remove(consid);
                }
            }
            //check current node
            if (SentinelModeStatus.getStatus() == SentinelModeStatus.Status.ONLINE) {
                nodeUp(currentNode);
            } else {
                nodeDown(currentNode);
            }
            //check other nodes
            for (ProxyNode node : allNodes) {
                if (node.equals(currentNode)) continue;
                boolean online = heartbeat(node);
                if (online) {
                    nodeUp(node);
                } else {
                    nodeDown(node);
                }
            }
        } catch (Exception e) {
            logger.error("schedule error", e);
        }
    }

    private boolean reloadNodes() {
        try {
            String string = ProxyDynamicConf.getString("proxy.sentinel.mode.nodes", null);
            if (string == null) {
                return false;
            }
            String[] split = string.split(",");
            Set<ProxyNode> nodes = new HashSet<>();
            for (String str : split) {
                ProxyNode node = ProxyNode.parseString(str);
                if (node == null) continue;
                nodes.add(node);
            }
            if (nodes.isEmpty()) {
                return false;
            }
            this.allNodes = new ArrayList<>(nodes);
            return true;
        } catch (Exception e) {
            logger.error("reload nodes error", e);
            return false;
        }
    }

    private boolean heartbeat(ProxyNode node) {
        try {
            RedisConnection connection = RedisConnectionHub.getInstance().get(null, node.getHost(), node.getCport(), null, null);
            CompletableFuture<Reply> future = connection.sendCommand(RedisCommand.SENTINEL.raw(), Utils.stringToBytes(heartbeat));
            int timeoutSeconds = ProxyDynamicConf.getInt("proxy.sentinel.mode.heartbeat.timeout.seconds", 20);
            Reply reply = future.get(timeoutSeconds, TimeUnit.SECONDS);
            if (reply instanceof StatusReply) {
                return ((StatusReply) reply).getStatus().equalsIgnoreCase(StatusReply.OK.getStatus());
            }
            logger.warn("proxy sentinel mode, heartbeat fail, node = {}, reply = {}", node, reply);
            return false;
        } catch (Exception e) {
            logger.error("proxy sentinel mode, heartbeat error, node = {}", node, e);
            return false;
        }
    }

    private Connection getConnection(String consid) {
        return CamelliaMapUtils.computeIfAbsent(connectionMap, consid, k -> new Connection());
    }

    private ProxyNode selectOnlineNode(ChannelInfo channelInfo) {
        ProxyNode target;
        try {
            String id = channelInfo.getSourceAddress();
            if (id == null) {
                id = channelInfo.getConsid();
            }
            if (id == null) {
                id = UUID.randomUUID().toString();
            }
            int size = onlineNodes.size();
            int index = Math.abs(id.hashCode()) % size;
            target = onlineNodes.get(index);
        } catch (Exception e) {
            try {
                if (!onlineNodes.isEmpty()) {
                    target = onlineNodes.get(0);
                } else {
                    target = currentNode;
                }
            } catch (Exception ex) {
                target = currentNode;
            }
        }
        return target;
    }

    private synchronized void nodeDown(ProxyNode proxyNode) {
        if (onlineNodes.contains(proxyNode)) {
            //update
            List<ProxyNode> list = new ArrayList<>(onlineNodes);
            list.remove(proxyNode);
            Collections.sort(list);
            onlineNodes = list;
            logger.warn("proxy node = {} down!", proxyNode);
            //notify
            Set<String> set = new HashSet<>(connectionMap.keySet());
            for (String consid : set) {
                Connection connection = connectionMap.get(consid);
                if (connection == null) continue;
                if (!connection.subscribe) continue;
                if (connection.channelInfo == null || connection.proxyNode == null) continue;
                if (connection.proxyNode.equals(proxyNode)) {
                    ChannelInfo channelInfo = connection.channelInfo;
                    ProxyNode target = selectOnlineNode(channelInfo);
                    notify(channelInfo, connection.proxyNode, target);
                    logger.info("notify client switch proxy for node down, client = {}, old proxy = {}, new proxy = {}",
                            channelInfo.getLAddr(), connection.proxyNode, target);
                    connection.proxyNode = target;
                }
            }
        }
    }

    private synchronized void nodeUp(ProxyNode proxyNode) {
        if (!onlineNodes.contains(proxyNode)) {
            //update
            List<ProxyNode> list = new ArrayList<>(onlineNodes);
            list.add(proxyNode);
            Collections.sort(list);
            onlineNodes = list;
            logger.info("proxy node = {} up!", proxyNode);
            //notify
            Set<String> set = new HashSet<>(connectionMap.keySet());
            for (String consid : set) {
                Connection connection = connectionMap.get(consid);
                if (connection == null) continue;
                if (!connection.subscribe) continue;
                if (connection.channelInfo == null || connection.proxyNode == null) continue;
                ChannelInfo channelInfo = connection.channelInfo;
                ProxyNode target = selectOnlineNode(channelInfo);
                if (!connection.proxyNode.equals(target)) {
                    notify(channelInfo, connection.proxyNode, target);
                    logger.info("notify client switch proxy for load balance, client = {}, old proxy = {}, new proxy = {}",
                            channelInfo.getLAddr(), connection.proxyNode, target);
                    connection.proxyNode = target;
                }
            }
        }
    }

    private void notify(ChannelInfo channelInfo, ProxyNode oldNode, ProxyNode target) {
        Reply[] replies = new Reply[3];
        replies[0] = new BulkReply(Utils.stringToBytes("message"));
        replies[1] = new BulkReply(Utils.stringToBytes("+switch-master"));
        String msg = masterName + " " + oldNode.getHost() + " " + oldNode.getPort() + " " + target.getHost() + " " + target.getPort();
        replies[2] = new BulkReply(Utils.stringToBytes(msg));
        MultiBulkReply reply = new MultiBulkReply(replies);
        channelInfo.getCommandTaskQueue().reply(RedisCommand.SUBSCRIBE, reply, false, false);
    }

    private static class Connection {
        ChannelInfo channelInfo;
        ProxyNode proxyNode;
        boolean subscribe = false;
        boolean auth = false;
    }
}