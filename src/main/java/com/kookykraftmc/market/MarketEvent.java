package com.kookykraftmc.market;

/**
 * Simple POJO representing a synchronization event stored in MySQL.
 */
public class MarketEvent {
    private final int id;
    private final MarketEventType type;
    private final String item;

    public MarketEvent(int id, MarketEventType type, String item) {
        this.id = id;
        this.type = type;
        this.item = item;
    }

    public int getId() {
        return id;
    }

    public MarketEventType getType() {
        return type;
    }

    public String getItem() {
        return item;
    }
}
