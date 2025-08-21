package com.kookykraftmc.market;

import org.slf4j.Logger;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Basic MySQL backed storage used to synchronize data between servers.
 *
 * <p>This service manages a {@code market_events} table that stores changes
 * that other servers should apply locally.</p>
 */
public class MySqlStorageService {

    private final DataSource dataSource;
    private final Logger logger;
    private static final String EVENTS_TABLE = "market_events";
    private static final String LISTINGS_TABLE = "listings";
    private static final String BLACKLIST_TABLE = "blacklist";
    private static final String UUID_CACHE_TABLE = "uuid_cache";

    public MySqlStorageService(DataSource dataSource, Logger logger) throws SQLException {
        this.dataSource = dataSource;
        this.logger = logger;
        init();
    }

    private void init() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + EVENTS_TABLE + " (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "type VARCHAR(64) NOT NULL," +
                    "item VARCHAR(255) NOT NULL," +
                    "processed TINYINT(1) DEFAULT 0," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
        }
    }

    public void insertBlacklistEvent(String type, String item) {
        String sql = "INSERT INTO " + EVENTS_TABLE + "(type, item) VALUES (?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, type);
            ps.setString(2, item);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to insert blacklist event", e);
        }
    }

    public List<MarketEvent> pollEvents() {
        List<MarketEvent> events = new ArrayList<>();
        String sql = "SELECT id, type, item FROM " + EVENTS_TABLE + " WHERE processed = 0";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                events.add(new MarketEvent(rs.getInt("id"), rs.getString("type"), rs.getString("item")));
            }
        } catch (SQLException e) {
            logger.error("Failed to poll events", e);
        }
        return events;
    }

    public void markProcessed(int id) {
        String sql = "UPDATE " + EVENTS_TABLE + " SET processed = 1 WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to mark event as processed", e);
        }
    }

    // --------- Blacklist ---------

    public List<String> getBlacklistedItems() {
        List<String> items = new ArrayList<>();
        String sql = "SELECT item FROM " + BLACKLIST_TABLE;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                items.add(rs.getString("item"));
            }
        } catch (SQLException e) {
            logger.error("Failed to load blacklist", e);
        }
        return items;
    }

    public void addBlacklistItem(String item) {
        String sql = "INSERT INTO " + BLACKLIST_TABLE + "(item) VALUES (?) ON DUPLICATE KEY UPDATE item=item";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, item);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to add blacklist item", e);
        }
    }

    public void removeBlacklistItem(String item) {
        String sql = "DELETE FROM " + BLACKLIST_TABLE + " WHERE item = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, item);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to remove blacklist item", e);
        }
    }

    // --------- UUID Cache ---------

    public Map<String, String> getUuidCache() {
        Map<String, String> map = new HashMap<>();
        String sql = "SELECT uuid, name FROM " + UUID_CACHE_TABLE;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                map.put(rs.getString("uuid"), rs.getString("name"));
            }
        } catch (SQLException e) {
            logger.error("Failed to load uuid cache", e);
        }
        return map;
    }

    public void upsertUuidCache(String uuid, String name) {
        String sql = "INSERT INTO " + UUID_CACHE_TABLE + "(uuid, name) VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ps.setString(2, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to upsert uuid cache", e);
        }
    }

    // --------- Listings ---------

    public List<ListingRecord> getListings() {
        List<ListingRecord> listings = new ArrayList<>();
        String sql = "SELECT id, seller_uuid, item, stock, price, quantity FROM " + LISTINGS_TABLE;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                listings.add(new ListingRecord(
                        rs.getInt("id"),
                        rs.getString("seller_uuid"),
                        rs.getString("item"),
                        rs.getInt("stock"),
                        rs.getInt("price"),
                        rs.getInt("quantity")));
            }
        } catch (SQLException e) {
            logger.error("Failed to load listings", e);
        }
        return listings;
    }

    public void insertListing(ListingRecord listing) {
        String sql = "INSERT INTO " + LISTINGS_TABLE + "(id, seller_uuid, item, stock, price, quantity) " +
                "VALUES (?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE seller_uuid=VALUES(seller_uuid), item=VALUES(item), stock=VALUES(stock), " +
                "price=VALUES(price), quantity=VALUES(quantity)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, listing.getId());
            ps.setString(2, listing.getSellerUuid());
            ps.setString(3, listing.getItem());
            ps.setInt(4, listing.getStock());
            ps.setInt(5, listing.getPrice());
            ps.setInt(6, listing.getQuantity());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to insert listing", e);
        }
    }

    public void updateListingStock(int id, int stock) {
        String sql = "UPDATE " + LISTINGS_TABLE + " SET stock = ? WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, stock);
            ps.setInt(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to update listing stock", e);
        }
    }

    public void removeListing(int id) {
        String sql = "DELETE FROM " + LISTINGS_TABLE + " WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to remove listing", e);
        }
    }
}
