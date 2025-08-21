package com.kookykraftmc.market;

/**
 * Represents a market listing stored in MySQL.
 */
public class ListingRecord {
    private final int id;
    private final String sellerUuid;
    private final String item;
    private final int stock;
    private final int price;
    private final int quantity;

    public ListingRecord(int id, String sellerUuid, String item, int stock, int price, int quantity) {
        this.id = id;
        this.sellerUuid = sellerUuid;
        this.item = item;
        this.stock = stock;
        this.price = price;
        this.quantity = quantity;
    }

    public int getId() {
        return id;
    }

    public String getSellerUuid() {
        return sellerUuid;
    }

    public String getItem() {
        return item;
    }

    public int getStock() {
        return stock;
    }

    public int getPrice() {
        return price;
    }

    public int getQuantity() {
        return quantity;
    }
}
