package com.kookykraftmc.market.storage;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Transaction;

import java.util.*;
import java.util.stream.Collectors;

/**
 * StorageService implementation backed by Redis.
 */
public class RedisStorageService implements StorageService {

    private final JedisPool pool;
    private final String serverName;

    private String key(String id) { return "market:" + serverName + ":" + id; }
    private String lastIdKey() { return "market:" + serverName + ":lastID"; }
    private String forSaleKey() { return "market:" + serverName + ":open"; }
    private static final String BLACKLIST = "market:blacklist";
    private static final String UUID_CACHE = "market:uuidcache";

    public RedisStorageService(String host, int port, String password, String serverName) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(128);
        if (password != null && !password.isEmpty()) {
            this.pool = new JedisPool(config, host, port, 0, password);
        } else {
            this.pool = new JedisPool(config, host, port, 0);
        }
        this.serverName = serverName;
    }

    public JedisPool getPool() {
        return pool;
    }

    @Override
    public int createListing(Listing listing) {
        try (Jedis jedis = pool.getResource()) {
            if (!jedis.exists(lastIdKey())) {
                jedis.set(lastIdKey(), String.valueOf(1));
            }
            int id = Integer.parseInt(jedis.get(lastIdKey()));
            String key = key(String.valueOf(id));
            Transaction m = jedis.multi();
            m.hset(key, "Item", listing.getItem());
            m.hset(key, "Seller", listing.getSeller());
            m.hset(key, "Stock", String.valueOf(listing.getStock()));
            m.hset(key, "Price", String.valueOf(listing.getPrice()));
            m.hset(key, "Quantity", String.valueOf(listing.getQuantity()));
            m.exec();
            jedis.hset(forSaleKey(), String.valueOf(id), listing.getSeller());
            jedis.incr(lastIdKey());
            return id;
        }
    }

    @Override
    public Optional<Listing> getListing(int id) {
        try (Jedis jedis = pool.getResource()) {
            if (!jedis.hexists(forSaleKey(), String.valueOf(id))) return Optional.empty();
            Map<String, String> map = jedis.hgetAll(key(String.valueOf(id)));
            return Optional.of(new Listing(id, map.get("Item"), map.get("Seller"),
                    Integer.parseInt(map.get("Stock")), Integer.parseInt(map.get("Price")),
                    Integer.parseInt(map.get("Quantity"))));
        }
    }

    @Override
    public List<Listing> getListings() {
        try (Jedis jedis = pool.getResource()) {
            Set<String> ids = jedis.hgetAll(forSaleKey()).keySet();
            List<Listing> list = new ArrayList<>();
            for (String id : ids) {
                Map<String, String> map = jedis.hgetAll(key(id));
                if (map.isEmpty()) continue;
                list.add(new Listing(Integer.parseInt(id), map.get("Item"), map.get("Seller"),
                        Integer.parseInt(map.get("Stock")), Integer.parseInt(map.get("Price")),
                        Integer.parseInt(map.get("Quantity"))));
            }
            return list;
        }
    }

    @Override
    public void updateListing(Listing listing) {
        try (Jedis jedis = pool.getResource()) {
            String key = key(String.valueOf(listing.getId()));
            jedis.hset(key, "Item", listing.getItem());
            jedis.hset(key, "Seller", listing.getSeller());
            jedis.hset(key, "Stock", String.valueOf(listing.getStock()));
            jedis.hset(key, "Price", String.valueOf(listing.getPrice()));
            jedis.hset(key, "Quantity", String.valueOf(listing.getQuantity()));
        }
    }

    @Override
    public void removeListing(int id) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hdel(forSaleKey(), String.valueOf(id));
        }
    }

    @Override
    public Set<String> getBlacklist() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.hgetAll(BLACKLIST).keySet();
        }
    }

    @Override
    public void addToBlacklist(String item) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(BLACKLIST, item, String.valueOf(true));
        }
    }

    @Override
    public void removeFromBlacklist(String item) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hdel(BLACKLIST, item);
        }
    }

    @Override
    public void updateUUIDCache(String uuid, String name) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(UUID_CACHE, uuid, name);
        }
    }

    @Override
    public String getNameFromUUID(String uuid) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.hget(UUID_CACHE, uuid);
        }
    }

    @Override
    public void close() {
        pool.close();
    }
}
