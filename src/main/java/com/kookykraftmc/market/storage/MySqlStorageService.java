package com.kookykraftmc.market.storage;

import com.kookykraftmc.market.MarketEvent;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * StorageService implementation backed by MySQL using HikariCP.
 */
public class MySqlStorageService implements StorageService {

    private final HikariDataSource dataSource;
    private static final String EVENTS_TABLE = "market_events";

    public MySqlStorageService(HikariDataSource dataSource) throws SQLException {
        this.dataSource = dataSource;
        initEventsTable();
    }

    private void initEventsTable() throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + EVENTS_TABLE + " (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "type VARCHAR(64) NOT NULL," +
                    "item VARCHAR(255) NOT NULL," +
                    "processed TINYINT(1) DEFAULT 0," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
        }
    }

    // ---- Listing operations ----
    @Override
    public int createListing(Listing listing) {
        String sql = "INSERT INTO listings (seller_uuid,item,stock,price,quantity) VALUES (?,?,?,?,?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, listing.getSeller());
            ps.setString(2, listing.getItem());
            ps.setInt(3, listing.getStock());
            ps.setInt(4, listing.getPrice());
            ps.setInt(5, listing.getQuantity());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return -1;
    }

    @Override
    public Optional<Listing> getListing(int id) {
        String sql = "SELECT id,seller_uuid,item,stock,price,quantity FROM listings WHERE id=?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new Listing(rs.getInt("id"), rs.getString("item"),
                            rs.getString("seller_uuid"), rs.getInt("stock"), rs.getInt("price"), rs.getInt("quantity")));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return Optional.empty();
    }

    @Override
    public List<Listing> getListings() {
        List<Listing> list = new ArrayList<>();
        String sql = "SELECT id,seller_uuid,item,stock,price,quantity FROM listings";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Listing(rs.getInt("id"), rs.getString("item"), rs.getString("seller_uuid"),
                        rs.getInt("stock"), rs.getInt("price"), rs.getInt("quantity")));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    @Override
    public void updateListing(Listing listing) {
        String sql = "UPDATE listings SET item=?,seller_uuid=?,stock=?,price=?,quantity=? WHERE id=?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, listing.getItem());
            ps.setString(2, listing.getSeller());
            ps.setInt(3, listing.getStock());
            ps.setInt(4, listing.getPrice());
            ps.setInt(5, listing.getQuantity());
            ps.setInt(6, listing.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void removeListing(int id) {
        String sql = "DELETE FROM listings WHERE id=?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ---- Blacklist operations ----
    @Override
    public Set<String> getBlacklist() {
        Set<String> set = new HashSet<>();
        String sql = "SELECT item FROM blacklist";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                set.add(rs.getString("item"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return set;
    }

    @Override
    public void addToBlacklist(String item) {
        String sql = "INSERT IGNORE INTO blacklist(item) VALUES (?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, item);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        insertBlacklistEvent("BLACKLIST_ADD", item);
    }

    @Override
    public void removeFromBlacklist(String item) {
        String sql = "DELETE FROM blacklist WHERE item=?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, item);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        insertBlacklistEvent("BLACKLIST_REMOVE", item);
    }

    // ---- UUID cache ----
    @Override
    public void updateUUIDCache(String uuid, String name) {
        String sql = "INSERT INTO uuid_cache(uuid,name) VALUES(?,?) ON DUPLICATE KEY UPDATE name=VALUES(name)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ps.setString(2, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public String getNameFromUUID(String uuid) {
        String sql = "SELECT name FROM uuid_cache WHERE uuid=?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("name");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    // ---- Event synchronization ----
    public void insertBlacklistEvent(String type, String item) {
        String sql = "INSERT INTO " + EVENTS_TABLE + "(type,item) VALUES(?,?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type);
            ps.setString(2, item);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public List<MarketEvent> pollEvents() {
        List<MarketEvent> events = new ArrayList<>();
        String sql = "SELECT id,type,item FROM " + EVENTS_TABLE + " WHERE processed=0";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                events.add(new MarketEvent(rs.getInt("id"), rs.getString("type"), rs.getString("item")));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return events;
    }

    public void markProcessed(int id) {
        String sql = "UPDATE " + EVENTS_TABLE + " SET processed=1 WHERE id=?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
