package com.kookykraftmc.market;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Basic MySQL backed storage used to synchronize data between servers.
 *
 * <p>This service manages a {@code market_events} table that stores changes
 * that other servers should apply locally.</p>
 */
public class MySqlStorageService {

    private final Connection connection;
    private static final String EVENTS_TABLE = "market_events";

    public MySqlStorageService(Connection connection) throws SQLException {
        this.connection = connection;
        init();
    }

    private void init() throws SQLException {
        try (Statement st = connection.createStatement()) {
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
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, type);
            ps.setString(2, item);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public List<MarketEvent> pollEvents() {
        List<MarketEvent> events = new ArrayList<>();
        String sql = "SELECT id, type, item FROM " + EVENTS_TABLE + " WHERE processed = 0";
        try (PreparedStatement ps = connection.prepareStatement(sql);
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
        String sql = "UPDATE " + EVENTS_TABLE + " SET processed = 1 WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
