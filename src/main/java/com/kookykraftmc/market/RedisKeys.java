package com.kookykraftmc.market;

/**
 * Created by TimeTheCat on 4/3/2017.
 */
public class RedisKeys {
    public static final String UUID_CACHE = "market:uuidcache";
    public static final String BLACKLIST = "market:blacklist";

    public static String lastMarketId() {
        return lastMarketId(Market.instance.getServerName());
    }

    public static String lastMarketId(String serverName) {
        return "market:" + serverName + ":lastID";
    }

    public static String marketItemKey(String id) {
        return marketItemKey(Market.instance.getServerName(), id);
    }

    public static String marketItemKey(String serverName, String id) {
        return "market:" + serverName + ":" + id;
    }

    public static String forSale() {
        return forSale(Market.instance.getServerName());
    }

    public static String forSale(String serverName) {
        return "market:" + serverName + ":open";
    }
}
