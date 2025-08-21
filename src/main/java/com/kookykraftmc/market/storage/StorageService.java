package com.kookykraftmc.market.storage;

import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.item.ItemType;
import org.spongepowered.api.item.inventory.ItemStack;
import org.spongepowered.api.service.economy.account.UniqueAccount;
import org.spongepowered.api.service.pagination.PaginationList;

import java.util.List;
import java.util.UUID;

/**
 * Abstraction for different storage backends used by the market.
 * Implementations are expected to provide basic CRUD operations for
 * listings, UUID cache and blacklist management.
 */
public interface StorageService {

    // Blacklist handling
    List<String> loadBlacklist();
    boolean blacklistAdd(String id);
    boolean blacklistRemove(String id);

    // UUID cache
    void cacheUUID(String uuid, String name);

    // Listing management
    int addListing(Player player, ItemStack itemStack, int quantityPerSale, int price);
    PaginationList getListings();
    List<ItemStack> removeListing(String id, String uuid, boolean staff);
    PaginationList getListing(String id);
    boolean addStock(ItemStack itemStack, String id, UUID uuid);
    ItemStack purchase(UniqueAccount uniqueAccount, String id);

    // Search helpers
    PaginationList searchForItem(ItemType itemType);
    PaginationList searchForUUID(UUID uniqueId);
}
