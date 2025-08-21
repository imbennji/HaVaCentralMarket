package com.kookykraftmc.market;

import org.spongepowered.api.Game;
import org.spongepowered.api.block.BlockType;
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

import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.hocon.HoconConfigurationLoader;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import org.spongepowered.api.data.DataView;
import org.spongepowered.api.data.persistence.DataTranslators;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/**
 * MySQL based storage implementation.
 */
public class MySqlStorageService implements StorageService {

    private final DataSource dataSource;
    private final Game game;

    public MySqlStorageService(DataSource dataSource, Game game) {
        this.dataSource = dataSource;
        this.game = game;
    }

    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    private ConfigurationLoader<CommentedConfigurationNode> loaderFromWriter(StringWriter stringWriter) {
        return HoconConfigurationLoader.builder().setSink(() -> new BufferedWriter(stringWriter)).build();
    }

    private ConfigurationLoader<CommentedConfigurationNode> loaderFromReader(StringReader stringReader) {
        return HoconConfigurationLoader.builder().setSource(() -> new BufferedReader(stringReader)).build();
    }

    private String serializeItem(ItemStack itemStack) {
        DataView dataView = itemStack.toContainer();
        ConfigurationNode node = DataTranslators.CONFIGURATION_NODE.translate(dataView);
        StringWriter stringWriter = new StringWriter();
        try {
            loaderFromWriter(stringWriter).save(node);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return stringWriter.toString();
    }

    private Optional<ItemStack> deserializeItemStack(String item) {
        ConfigurationNode node = null;
        try {
            node = loaderFromReader(new StringReader(item)).load();
        } catch (IOException e) {
            e.printStackTrace();
        }
        DataView dataView = DataTranslators.CONFIGURATION_NODE.translate(node);
        return game.getDataManager().deserialize(ItemStack.class, dataView);
    }

    private boolean matchItemStacks(ItemStack is0, ItemStack is1) {
        return new DataComparator().compare(is0, is1) == 0;
    }

    @Override
    public void updateUUIDCache(String uuid, String name) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO uuid_cache (uuid, name) VALUES (?, ?) ON DUPLICATE KEY UPDATE name=VALUES(name)")) {
            ps.setString(1, uuid);
            ps.setString(2, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public String getCachedName(String uuid) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT name FROM uuid_cache WHERE uuid=?")) {
            ps.setString(1, uuid);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("name");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public List<String> getBlacklistedItems() {
        List<String> list = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT item FROM blacklist");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(rs.getString("item"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    @Override
    public boolean addBlacklistedItem(String id) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT IGNORE INTO blacklist (item) VALUES (?)")) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean removeBlacklistedItem(String id) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM blacklist WHERE item=?")) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    private boolean isBlacklisted(ItemStack itemStack) {
        Optional<BlockType> type = itemStack.getItem().getBlock();
        String id = type.map(blockType -> blockType.getDefaultState().getId())
                .orElseGet(() -> itemStack.getItem().getId());
        return getBlacklistedItems().contains(id);
    }

    @Override
    public int addListing(Player player, ItemStack itemStack, int quantityPerSale, int price) {
        if (itemStack.getQuantity() < quantityPerSale || quantityPerSale <= 0 || isBlacklisted(itemStack)) return 0;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO listings (seller_uuid, item, stock, price, quantity) VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, player.getUniqueId().toString());
            ps.setString(2, serializeItem(itemStack));
            ps.setInt(3, itemStack.getQuantity());
            ps.setInt(4, price);
            ps.setInt(5, quantityPerSale);
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    @Override
    public List<ItemStack> removeListing(String id, String uuid, boolean staff) {
        List<ItemStack> stacks = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT seller_uuid, item, stock FROM listings WHERE id=?")) {
            ps.setInt(1, Integer.parseInt(id));
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            if (!rs.getString("seller_uuid").equals(uuid) && !staff) return null;
            int inStock = rs.getInt("stock");
            ItemStack listingIS = deserializeItemStack(rs.getString("item")).get();
            int stacksInStock = inStock / listingIS.getMaxStackQuantity();
            for (int i = 0; i < stacksInStock; i++) {
                stacks.add(listingIS.copy());
            }
            if (inStock % listingIS.getMaxStackQuantity() != 0) {
                ItemStack extra = listingIS.copy();
                extra.setQuantity(inStock % listingIS.getMaxStackQuantity());
                stacks.add(extra);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM listings WHERE id=?")) {
            ps.setInt(1, Integer.parseInt(id));
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return stacks;
    }

    @Override
    public PaginationList getListings() {
        List<Text> texts = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id, item, seller_uuid, price, quantity FROM listings");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int id = rs.getInt("id");
                Optional<ItemStack> is = deserializeItemStack(rs.getString("item"));
                if (!is.isPresent()) continue;
                Text.Builder l = Text.builder();
                l.append(Texts.quickItemFormat(is.get()));
                l.append(Text.of(" "));
                l.append(Text.of(TextColors.GREEN, rs.getInt("quantity") + "x"));
                l.append(Text.of(" "));
                l.append(Text.of(TextColors.WHITE, "Seller:"));
                l.append(Text.of(TextColors.LIGHT_PURPLE, " " + getCachedName(rs.getString("seller_uuid"))));
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
        return Market.instance.getPaginationService().builder().contents(texts).title(Texts.MARKET_LISTINGS).build();
    }

    @Override
    public PaginationList getListing(String id) {
        List<Text> texts = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM listings WHERE id=?")) {
            ps.setInt(1, Integer.parseInt(id));
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            texts.add(Texts.quickItemFormat(deserializeItemStack(rs.getString("item")).get()));
            texts.add(Text.of("Seller: " + getCachedName(rs.getString("seller_uuid"))));
            texts.add(Text.of("Stock: " + rs.getInt("stock")));
            texts.add(Text.of("Price: " + rs.getInt("price")));
            texts.add(Text.of("Quantity: " + rs.getInt("quantity")));
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }
        texts.add(Text.builder()
                .append(Text.builder()
                        .color(TextColors.GREEN)
                        .append(Text.of("[Buy]"))
                        .onClick(TextActions.suggestCommand("/market buy " + id))
                        .build())
                .append(Text.of(" "))
                .append(Text.builder()
                        .color(TextColors.GREEN)
                        .append(Text.of("[QuickBuy]"))
                        .onClick(TextActions.runCommand("/market buy " + id))
                        .onHover(TextActions.showText(Text.of("Click here to run the command to buy the item.")))
                        .build())
                .build());
        return Market.instance.getPaginationService().builder().title(Texts.MARKET_LISTING(id)).contents(texts).build();
    }

    @Override
    public boolean addStock(ItemStack itemStack, String id, UUID uuid) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT seller_uuid, item, stock FROM listings WHERE id=?")) {
            ps.setInt(1, Integer.parseInt(id));
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return false;
            if (!rs.getString("seller_uuid").equals(uuid.toString())) return false;
            ItemStack listingStack = deserializeItemStack(rs.getString("item")).get();
            if (!matchItemStacks(listingStack, itemStack)) return false;
            int stock = rs.getInt("stock");
            int quan = itemStack.getQuantity() + stock;
            try (PreparedStatement ups = conn.prepareStatement("UPDATE listings SET stock=? WHERE id=?")) {
                ups.setInt(1, quan);
                ups.setInt(2, Integer.parseInt(id));
                ups.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public ItemStack purchase(UniqueAccount uniqueAccount, String id) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM listings WHERE id=?")) {
            ps.setInt(1, Integer.parseInt(id));
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            TransactionResult tr = uniqueAccount.transfer(
                    Market.instance.getEconomyService().getOrCreateAccount(UUID.fromString(rs.getString("seller_uuid"))).get(),
                    Market.instance.getEconomyService().getDefaultCurrency(),
                    BigDecimal.valueOf(rs.getInt("price")),
                    Market.instance.getMarketCause()
            );
            if (tr.getResult().equals(ResultType.SUCCESS)) {
                ItemStack is = deserializeItemStack(rs.getString("item")).get();
                int quant = rs.getInt("quantity");
                int inStock = rs.getInt("stock");
                int newQuant = inStock - quant;
                if (newQuant < quant) {
                    try (PreparedStatement del = conn.prepareStatement("DELETE FROM listings WHERE id=?")) {
                        del.setInt(1, Integer.parseInt(id));
                        del.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ups = conn.prepareStatement("UPDATE listings SET stock=? WHERE id=?")) {
                        ups.setInt(1, newQuant);
                        ups.setInt(2, Integer.parseInt(id));
                        ups.executeUpdate();
                    }
                }
                ItemStack nis = is.copy();
                nis.setQuantity(quant);
                return nis;
            } else {
                return null;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public PaginationList searchForItem(ItemType itemType) {
        List<Text> texts = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id, item, seller_uuid, price, quantity FROM listings");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Optional<ItemStack> ooi = deserializeItemStack(rs.getString("item"));
                if (!ooi.isPresent() || !ooi.get().getItem().equals(itemType)) continue;
                Text.Builder l = Text.builder();
                ItemStack is = ooi.get();
                l.append(Texts.quickItemFormat(is));
                l.append(Text.of(" "));
                l.append(Text.of(TextColors.GREEN, rs.getInt("quantity") + "x"));
                l.append(Text.of(" "));
                l.append(Text.of(TextColors.WHITE, "Seller:"));
                l.append(Text.of(TextColors.LIGHT_PURPLE, " " + getCachedName(rs.getString("seller_uuid"))));
                l.append(Text.of(" "));
                l.append(Text.builder()
                        .color(TextColors.GREEN)
                        .onClick(TextActions.runCommand("/market check " + rs.getInt("id")))
                        .append(Text.of("[Info]"))
                        .onHover(TextActions.showText(Text.of("View more info about this listing.")))
                        .build());
                texts.add(l.build());
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return Market.instance.getPaginationService().builder().contents(texts).title(Texts.MARKET_SEARCH).build();
    }

    @Override
    public PaginationList searchForUUID(UUID uniqueId) {
        List<Text> texts = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id, item, seller_uuid, price, quantity FROM listings WHERE seller_uuid=?")) {
            ps.setString(1, uniqueId.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Optional<ItemStack> ooi = deserializeItemStack(rs.getString("item"));
                if (!ooi.isPresent()) continue;
                Text.Builder l = Text.builder();
                ItemStack is = ooi.get();
                l.append(Texts.quickItemFormat(is));
                l.append(Text.of(" "));
                l.append(Text.of(TextColors.GREEN, rs.getInt("quantity") + "x"));
                l.append(Text.of(" "));
                l.append(Text.of(TextColors.WHITE, "Seller:"));
                l.append(Text.of(TextColors.LIGHT_PURPLE, " " + getCachedName(rs.getString("seller_uuid"))));
                l.append(Text.of(" "));
                l.append(Text.builder()
                        .color(TextColors.GREEN)
                        .onClick(TextActions.runCommand("/market check " + rs.getInt("id")))
                        .append(Text.of("[Info]"))
                        .onHover(TextActions.showText(Text.of("View more info about this listing.")))
                        .build());
                texts.add(l.build());
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return Market.instance.getPaginationService().builder().contents(texts).title(Texts.MARKET_SEARCH).build();
    }
}
