package com.kookykraftmc.market;

import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.item.ItemType;
import org.spongepowered.api.item.inventory.ItemStack;
import org.spongepowered.api.service.economy.account.UniqueAccount;
import org.spongepowered.api.service.pagination.PaginationList;

import java.util.List;
import java.util.UUID;

/**
 * Abstraction for the storage backend. Implementations may persist data in
 * Redis, MySQL or any other system.
 */
public interface StorageService {

    // UUID cache operations
    void updateUUIDCache(String uuid, String name);
    String getCachedName(String uuid);

    // blacklist operations
    List<String> getBlacklistedItems();
    boolean addBlacklistedItem(String id);
    boolean removeBlacklistedItem(String id);

    // listing operations
    int addListing(Player player, ItemStack itemStack, int quantityPerSale, int price);
    List<ItemStack> removeListing(String id, String uuid, boolean staff);
    PaginationList getListings();
    PaginationList getListing(String id);
    boolean addStock(ItemStack itemStack, String id, UUID uuid);
    ItemStack purchase(UniqueAccount uniqueAccount, String id);
    PaginationList searchForItem(ItemType itemType);
    PaginationList searchForUUID(UUID uniqueId);
}
