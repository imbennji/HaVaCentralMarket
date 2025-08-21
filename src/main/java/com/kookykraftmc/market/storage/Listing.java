package com.kookykraftmc.market.storage;

/**
 * Simple data holder for a market listing.
 */
public class Listing {
    private int id;
    private String item;
    private String seller;
    private int stock;
    private int price;
    private int quantity;

    public Listing(int id, String item, String seller, int stock, int price, int quantity) {
        this.id = id;
        this.item = item;
        this.seller = seller;
        this.stock = stock;
        this.price = price;
        this.quantity = quantity;
    }

    public int getId() {
        return id;
    }

    public String getItem() {
        return item;
    }

    public String getSeller() {
        return seller;
    }

    public int getStock() {
        return stock;
    }

    public void setStock(int stock) {
        this.stock = stock;
    }

    public int getPrice() {
        return price;
    }

    public int getQuantity() {
        return quantity;
    }
}
