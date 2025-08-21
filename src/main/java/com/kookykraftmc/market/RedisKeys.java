package com.kookykraftmc.market;

/**
 * Created by TimeTheCat on 4/3/2017.
 */
public class RedisKeys {
    static final String UUID_CACHE = "market:uuidcache";
    static final String BLACKLIST = "market:blacklist";

    public static String lastMarketId() {
        return "market:" + Market.instance.getServerName() + ":lastID";
    }

    public static String marketItemKey(String id) {
        return "market:" + Market.instance.getServerName() + ":" + id;
    }

    public static String forSale() {
        return "market:" + Market.instance.getServerName() + ":open";
    }
}
