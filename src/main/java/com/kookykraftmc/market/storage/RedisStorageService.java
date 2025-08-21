package com.kookykraftmc.market.storage;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class RedisStorageService implements StorageService {

    private final JedisPool pool;

    public RedisStorageService(String host, int port, boolean usePassword, String password) {
        if (usePassword) {
            this.pool = new JedisPool(new JedisPoolConfig(), host, port, 0, password);
        } else {
            this.pool = new JedisPool(new JedisPoolConfig(), host, port, 0);
        }
    }

    public JedisPool getPool() {
        return pool;
    }
}
