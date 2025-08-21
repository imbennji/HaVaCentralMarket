package com.kookykraftmc.market.storage;

import com.kookykraftmc.market.Market;
import com.kookykraftmc.market.Texts;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.item.ItemType;
import org.spongepowered.api.item.inventory.ItemStack;
import org.spongepowered.api.service.economy.account.UniqueAccount;
import org.spongepowered.api.service.economy.transaction.ResultType;
import org.spongepowered.api.service.economy.transaction.TransactionResult;
import org.spongepowered.api.service.pagination.PaginationList;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.action.TextActions;
import org.spongepowered.api.text.format.TextColors;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/**
 * MySQL based implementation of {@link StorageService} using JDBC/HikariCP.
 */
public class MySqlStorageService implements StorageService {

    private final Market market;
    private final HikariDataSource dataSource;

    public MySqlStorageService(Market market, String host, int port, String database,
                               String username, String password) {
        this.market = market;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false");
        config.setUsername(username);
        config.setPassword(password);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        this.dataSource = new HikariDataSource(config);
        initTables();
    }

    private void initTables() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS listings (" +
                    "id INT PRIMARY KEY AUTO_INCREMENT," +
                    "seller VARCHAR(36)," +
                    "item TEXT," +
                    "stock INT," +
                    "price INT," +
                    "quantity INT" +
                    ")");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS uuid_cache (" +
                    "uuid VARCHAR(36) PRIMARY KEY," +
                    "name VARCHAR(16)" +
                    ")");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS blacklist (" +
                    "id VARCHAR(128) PRIMARY KEY" +
                    ")");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private String serializeItem(ItemStack itemStack) {
        return market.serializeItem(itemStack);
    }

    private Optional<ItemStack> deserializeItemStack(String data) {
        return market.deserializeItemStack(data);
    }

    private boolean matchItemStacks(ItemStack a, ItemStack b) {
        return market.matchItemStacks(a, b);
    }

    @Override
    public List<String> loadBlacklist() {
        List<String> list = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM blacklist");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(rs.getString("id"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    @Override
    public boolean blacklistAdd(String id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO blacklist(id) VALUES (?)")) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean blacklistRemove(String id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM blacklist WHERE id = ?")) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public void cacheUUID(String uuid, String name) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("REPLACE INTO uuid_cache(uuid,name) VALUES (?,?)")) {
            ps.setString(1, uuid);
            ps.setString(2, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private boolean checkForOtherListings(ItemStack itemStack, String seller) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT item FROM listings WHERE seller = ?")) {
            ps.setString(1, seller);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Optional<ItemStack> ooi = deserializeItemStack(rs.getString("item"));
                    if (ooi.isPresent() && matchItemStacks(ooi.get(), itemStack)) {
                        return true;
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public int addListing(Player player, ItemStack itemStack, int quantityPerSale, int price) {
        if (itemStack.getQuantity() < quantityPerSale || quantityPerSale <= 0 || market.isBlacklisted(itemStack))
            return 0;
        if (checkForOtherListings(itemStack, player.getUniqueId().toString())) return -1;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO listings(seller,item,stock,price,quantity) VALUES (?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, player.getUniqueId().toString());
            ps.setString(2, serializeItem(itemStack));
            ps.setInt(3, itemStack.getQuantity());
            ps.setInt(4, price);
            ps.setInt(5, quantityPerSale);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    @Override
    public PaginationList getListings() {
        List<Text> texts = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM listings");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String id = String.valueOf(rs.getInt("id"));
                Optional<ItemStack> is = deserializeItemStack(rs.getString("item"));
                if (!is.isPresent()) continue;
                Text.Builder l = Text.builder();
                l.append(Texts.quickItemFormat(is.get()));
                l.append(Text.of(" "));
                l.append(Text.of(TextColors.WHITE, "@"));
                l.append(Text.of(" "));
                l.append(Text.of(TextColors.GREEN, "$" + rs.getInt("price")));
                l.append(Text.of(" "));
                l.append(Text.of(TextColors.WHITE, "for"));
                l.append(Text.of(" "));
                l.append(Text.of(TextColors.GREEN, rs.getInt("quantity") + "x"));
                l.append(Text.of(" "));
                l.append(Text.of(TextColors.WHITE, "Seller:"));
                String sellerName = fetchUUIDName(rs.getString("seller"));
                l.append(Text.of(TextColors.LIGHT_PURPLE, " " + sellerName));
                l.append(Text.of(" "));
                l.append(Text.builder()
                        .color(TextColors.GREEN)
                        .onClick(TextActions.runCommand("/market check " + id))
                        .append(Text.of("[Info]"))
                        .onHover(TextActions.showText(Text.of("View more info about this listing.")))
                        .build());
                texts.add(l.build());
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return market.getPaginationService().builder().contents(texts).title(Texts.MARKET_LISTINGS).build();
    }

    private String fetchUUIDName(String uuid) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT name FROM uuid_cache WHERE uuid=?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("name");
                }
            }
        }
        return uuid;
    }

    @Override
    public List<ItemStack> removeListing(String id, String uuid, boolean staff) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM listings WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                if (!rs.getString("seller").equals(uuid) && !staff) return null;
                int inStock = rs.getInt("stock");
                Optional<ItemStack> ois = deserializeItemStack(rs.getString("item"));
                if (!ois.isPresent()) return null;
                ItemStack listingIS = ois.get();
                int stacksInStock = inStock / listingIS.getMaxStackQuantity();
                List<ItemStack> stacks = new ArrayList<>();
                for (int i = 0; i < stacksInStock; i++) {
                    stacks.add(listingIS.copy());
                }
                if (inStock % listingIS.getMaxStackQuantity() != 0) {
                    ItemStack extra = listingIS.copy();
                    extra.setQuantity(inStock % listingIS.getMaxStackQuantity());
                    stacks.add(extra);
                }
                try (PreparedStatement del = c.prepareStatement("DELETE FROM listings WHERE id=?")) {
                    del.setString(1, id);
                    del.executeUpdate();
                }
                return stacks;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public PaginationList getListing(String id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM listings WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                List<Text> texts = new ArrayList<>();
                Optional<ItemStack> is = deserializeItemStack(rs.getString("item"));
                is.ifPresent(itemStack -> texts.add(Texts.quickItemFormat(itemStack)));
                String sellerName = fetchUUIDName(rs.getString("seller"));
                texts.add(Text.of("Seller: " + sellerName));
                texts.add(Text.of("Price: " + rs.getInt("price")));
                texts.add(Text.of("Quantity: " + rs.getInt("quantity")));
                texts.add(Text.builder()
                        .color(TextColors.GREEN)
                        .append(Text.of("[Buy]"))
                        .onClick(TextActions.suggestCommand("/market buy " + id))
                        .build());
                texts.add(Text.builder()
                        .color(TextColors.GREEN)
                        .append(Text.of("[QuickBuy]"))
                        .onClick(TextActions.runCommand("/market buy " + id))
                        .onHover(TextActions.showText(Text.of("Click here to run the command to buy the item.")))
                        .build());
                return market.getPaginationService().builder().title(Texts.MARKET_LISTING(id)).contents(texts).build();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public boolean addStock(ItemStack itemStack, String id, UUID uuid) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM listings WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                if (!rs.getString("seller").equals(uuid.toString())) return false;
                Optional<ItemStack> o = deserializeItemStack(rs.getString("item"));
                if (!o.isPresent()) return false;
                if (!matchItemStacks(o.get(), itemStack)) return false;
                int stock = rs.getInt("stock");
                int quan = itemStack.getQuantity() + stock;
                try (PreparedStatement up = c.prepareStatement("UPDATE listings SET stock=? WHERE id=?")) {
                    up.setInt(1, quan);
                    up.setString(2, id);
                    up.executeUpdate();
                }
                return true;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public ItemStack purchase(UniqueAccount uniqueAccount, String id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM listings WHERE id=? FOR UPDATE")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                TransactionResult tr = uniqueAccount.transfer(
                        market.getEconomyService().getOrCreateAccount(UUID.fromString(rs.getString("seller"))).get(),
                        market.getEconomyService().getDefaultCurrency(),
                        BigDecimal.valueOf(rs.getInt("price")),
                        market.getMarketCause());
                if (!tr.getResult().equals(ResultType.SUCCESS)) return null;
                Optional<ItemStack> ois = deserializeItemStack(rs.getString("item"));
                if (!ois.isPresent()) return null;
                int quant = rs.getInt("quantity");
                int inStock = rs.getInt("stock");
                int newQuant = inStock - quant;
                if (newQuant < quant) {
                    try (PreparedStatement del = c.prepareStatement("DELETE FROM listings WHERE id=?")) {
                        del.setString(1, id);
                        del.executeUpdate();
                    }
                } else {
                    try (PreparedStatement up = c.prepareStatement("UPDATE listings SET stock=? WHERE id=?")) {
                        up.setInt(1, newQuant);
                        up.setString(2, id);
                        up.executeUpdate();
                    }
                }
                ItemStack nis = ois.get().copy();
                nis.setQuantity(quant);
                return nis;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public PaginationList searchForItem(ItemType itemType) {
        List<Text> texts = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM listings");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Optional<ItemStack> is = deserializeItemStack(rs.getString("item"));
                if (!is.isPresent() || !is.get().getItem().equals(itemType)) continue;
                String id = String.valueOf(rs.getInt("id"));
                Text.Builder l = Text.builder();
                l.append(Texts.quickItemFormat(is.get()));
                l.append(Text.of(" "));
                l.append(Text.of(TextColors.WHITE, "@"));
                l.append(Text.of(" "));
                l.append(Text.of(TextColors.GREEN, "$" + rs.getInt("price")));
                l.append(Text.of(" "));
                l.append(Text.of(TextColors.WHITE, "for"));
                l.append(Text.of(" "));
                l.append(Text.of(TextColors.GREEN, rs.getInt("quantity") + "x"));
                l.append(Text.of(" "));
                l.append(Text.of(TextColors.WHITE, "Seller:"));
                String sellerName = fetchUUIDName(rs.getString("seller"));
                l.append(Text.of(TextColors.LIGHT_PURPLE, " " + sellerName));
                l.append(Text.of(" "));
                l.append(Text.builder()
                        .color(TextColors.GREEN)
                        .onClick(TextActions.runCommand("/market check " + id))
                        .append(Text.of("[Info]"))
                        .onHover(TextActions.showText(Text.of("View more info about this listing.")))
                        .build());
                texts.add(l.build());
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        if (texts.isEmpty()) texts.add(Text.of(TextColors.RED, "No listings found."));
        return market.getPaginationService().builder().contents(texts).title(Texts.MARKET_SEARCH).build();
    }

    @Override
    public PaginationList searchForUUID(UUID uniqueId) {
        List<Text> texts = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM listings WHERE seller=?")) {
            ps.setString(1, uniqueId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Optional<ItemStack> is = deserializeItemStack(rs.getString("item"));
                    if (!is.isPresent()) continue;
                    String id = String.valueOf(rs.getInt("id"));
                    Text.Builder l = Text.builder();
                    l.append(Texts.quickItemFormat(is.get()));
                    l.append(Text.of(" "));
                    l.append(Text.of(TextColors.WHITE, "@"));
                    l.append(Text.of(" "));
                    l.append(Text.of(TextColors.GREEN, "$" + rs.getInt("price")));
                    l.append(Text.of(" "));
                    l.append(Text.of(TextColors.WHITE, "for"));
                    l.append(Text.of(" "));
                    l.append(Text.of(TextColors.GREEN, rs.getInt("quantity") + "x"));
                    l.append(Text.of(" "));
                    l.append(Text.of(TextColors.WHITE, "Seller:"));
                    String sellerName = fetchUUIDName(rs.getString("seller"));
                    l.append(Text.of(TextColors.LIGHT_PURPLE, " " + sellerName));
                    l.append(Text.of(" "));
                    l.append(Text.builder()
                            .color(TextColors.GREEN)
                            .onClick(TextActions.runCommand("/market check " + id))
                            .append(Text.of("[Info]"))
                            .onHover(TextActions.showText(Text.of("View more info about this listing.")))
                            .build());
                    texts.add(l.build());
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        if (texts.isEmpty()) texts.add(Text.of(TextColors.RED, "No listings found."));
        return market.getPaginationService().builder().contents(texts).title(Texts.MARKET_SEARCH).build();
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
