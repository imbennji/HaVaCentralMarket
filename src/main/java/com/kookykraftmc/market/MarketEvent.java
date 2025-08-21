package com.kookykraftmc.market;

/**
 * Simple POJO representing a synchronization event stored in MySQL.
 */
public class MarketEvent {
    private final int id;
    private final String type;
    private final String item;

    public MarketEvent(int id, String type, String item) {
        this.id = id;
        this.type = type;
        this.item = item;
    }

    public int getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getItem() {
        return item;
    }
}
