package com.netease.nim.camellia.hot.key.server.springboot.conf;

import com.netease.nim.camellia.hot.key.server.conf.HotKeyServerProperties;


/**
 * Created by caojiajun on 2023/5/10
 */
public class ConfUtils {

    public static HotKeyServerProperties parse(CamelliaHotKeyServerProperties serverProperties) {
        HotKeyServerProperties properties = new HotKeyServerProperties();
        properties.setNettyBossThread(serverProperties.getNetty().getBossThread());
        if (serverProperties.getNetty().getWorkThread() > 0) {
            properties.setNettyWorkThread(serverProperties.getNetty().getWorkThread());
        }

        if (serverProperties.getBizWorkThread() > 0) {
            properties.setBizWorkThread(serverProperties.getBizWorkThread());
        }
        properties.setBizQueueCapacity(serverProperties.getBizQueueCapacity());

        properties.setMaxNamespace(serverProperties.getMaxNamespace());
        properties.setHotKeyCacheCounterCapacity(serverProperties.getHotKeyCacheCounterCapacity());

        properties.setTopnCacheCounterCapacity(serverProperties.getTopnCacheCounterCapacity());
        properties.setTopnCheckMillis(serverProperties.getTopnCheckMillis());
        properties.setTopnScheduleSeconds(serverProperties.getTopnScheduleSeconds());
        properties.setTopnCount(serverProperties.getTopnCount());
        properties.setTopnRedisKeyPrefix(serverProperties.getTopnRedisKeyPrefix());
        properties.setTopnRedisExpireSeconds(serverProperties.getTopnRedisExpireSeconds());

        properties.setCallbackExecutorSize(serverProperties.getCallbackExecutorSize());
        properties.setHotKeyCallbackIntervalSeconds(serverProperties.getHotKeyCallbackIntervalSeconds());
        properties.setTopNCallbackClassName(serverProperties.getTopnCallbackClassName());
        properties.setHotKeyCallbackClassName(serverProperties.getHotKeyCallbackClassName());
        properties.setHotKeyConfigServiceClassName(serverProperties.getHotKeyConfigServiceClassName());

        properties.setTcpNoDelay(serverProperties.getNetty().isTcpNoDelay());
        properties.setSoKeepalive(serverProperties.getNetty().isSoKeepalive());
        properties.setSoBacklog(serverProperties.getNetty().getSoBacklog());
        properties.setSoRcvbuf(serverProperties.getNetty().getSoRcvbuf());
        properties.setSoSndbuf(serverProperties.getNetty().getSoSndbuf());
        properties.setWriteBufferWaterMarkLow(serverProperties.getNetty().getWriteBufferWaterMarkLow());
        properties.setWriteBufferWaterMarkHigh(serverProperties.getNetty().getWriteBufferWaterMarkHigh());

        return properties;
    }
}
