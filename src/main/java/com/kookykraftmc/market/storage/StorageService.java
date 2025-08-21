package com.kookykraftmc.market.storage;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Abstraction over the backend storage used by the market plugin.
 */
public interface StorageService {

    // Listing operations
    int createListing(Listing listing);
    Optional<Listing> getListing(int id);
    List<Listing> getListings();
    void updateListing(Listing listing);
    void removeListing(int id);

    // Blacklist operations
    Set<String> getBlacklist();
    void addToBlacklist(String item);
    void removeFromBlacklist(String item);

    // UUID cache operations
    void updateUUIDCache(String uuid, String name);
    String getNameFromUUID(String uuid);

    // Shutdown
    void close();
}
