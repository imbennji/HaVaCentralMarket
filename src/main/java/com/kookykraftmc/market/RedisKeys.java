package com.kookykraftmc.market;

/**
 * Created by TimeTheCat on 4/3/2017.
 */
public class RedisKeys {
    static final String UUID_CACHE = "market:uuidcache";
    public static String LAST_MARKET_ID = "market:" + Market.instance.getServerName() + ":lastID";
    public static String MARKET_ITEM_KEY(String id) { return "market:" + Market.instance.getServerName() + ":" + id; }
    public static String FOR_SALE = "market:" + Market.instance.getServerName()  + ":open";
    public static String BLACKLIST = "market:blacklist";
}
