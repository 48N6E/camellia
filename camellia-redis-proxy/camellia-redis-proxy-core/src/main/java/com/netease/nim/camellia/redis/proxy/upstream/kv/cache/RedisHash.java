package com.netease.nim.camellia.redis.proxy.upstream.kv.cache;

import com.netease.nim.camellia.redis.proxy.util.Utils;
import com.netease.nim.camellia.tools.utils.BytesKey;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by caojiajun on 2024/6/5
 */
public class RedisHash {
    private final Map<BytesKey, byte[]> map;

    public RedisHash(Map<BytesKey, byte[]> map) {
        this.map = map;
    }

    public RedisHash duplicate() {
        return new RedisHash(new HashMap<>(map));
    }

    public Map<BytesKey, byte[]> hset(Map<BytesKey, byte[]> fieldMap) {
        Map<BytesKey, byte[]> existsMap = new HashMap<>();
        for (Map.Entry<BytesKey, byte[]> entry : fieldMap.entrySet()) {
            byte[] put = map.put(entry.getKey(), entry.getValue());
            if (put != null) {
                existsMap.put(entry.getKey(), put);
            }
        }
        return existsMap;
    }

    public byte[] hset(BytesKey field, byte[] value) {
        return map.put(field, value);
    }

    public Map<BytesKey, byte[]> hdel(Collection<BytesKey> fields) {
        Map<BytesKey, byte[]> deleteMap = new HashMap<>();
        for (BytesKey field : fields) {
            byte[] remove = map.remove(field);
            if (remove != null) {
                deleteMap.put(field, remove);
            }
        }
        return deleteMap;
    }

    public byte[] hget(BytesKey key) {
        return map.get(key);
    }

    public int hstrlen(BytesKey key) {
        byte[] bytes = map.get(key);
        if (bytes == null) {
            return 0;
        }
        return Utils.bytesToString(bytes).length();
    }

    public int hexists(BytesKey key) {
        byte[] bytes = map.get(key);
        if (bytes == null) {
            return 0;
        }
        return 1;
    }

    public int hlen() {
        return map.size();
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public Map<BytesKey, byte[]> hgetAll() {
        return map;
    }

}
