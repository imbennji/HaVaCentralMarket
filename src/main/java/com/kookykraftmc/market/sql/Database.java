package com.kookykraftmc.market.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Collectors;

public class Database {

    private final HikariDataSource dataSource;
    private final Logger logger;

    public Database(String host, int port, String database, String username, String password, Logger logger) {
        this.logger = logger;

        // Ensure the target database exists before initializing the pool. Some
        // MySQL setups do not create databases automatically which would cause
        // later connection attempts to fail and no tables to be created.
        String baseJdbc = "jdbc:mysql://" + host + ":" + port + "/";
        try (Connection conn = DriverManager.getConnection(baseJdbc, username, password);
             Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + database + "`");
        } catch (SQLException e) {
            logger.error("Failed to create database {}", database, e);
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&serverTimezone=UTC");
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setPoolName("MarketHikari");
        this.dataSource = new HikariDataSource(config);
    }

    public HikariDataSource getDataSource() {
        return dataSource;
    }

    public void runMigrations() {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            InputStream in = getClass().getResourceAsStream("/sql/schema.sql");
            if (in == null) {
                logger.error("Could not load schema.sql");
                return;
            }
            String sql = new BufferedReader(new InputStreamReader(in))
                    .lines().collect(Collectors.joining("\n"));
            for (String statement : sql.split(";")) {
                String trimmed = statement.trim();
                if (!trimmed.isEmpty()) {
                    st.execute(trimmed);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to run database migrations", e);
        }
    }

    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
