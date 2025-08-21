package com.kookykraftmc.market;

import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.kookykraftmc.market.commands.MarketCommand;
import com.kookykraftmc.market.commands.subcommands.*;
import com.kookykraftmc.market.commands.subcommands.blacklist.BlacklistAddCommand;
import com.kookykraftmc.market.commands.subcommands.blacklist.BlacklistRemoveCommand;
import com.kookykraftmc.market.sql.Database;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.hocon.HoconConfigurationLoader;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import org.slf4j.Logger;
import org.spongepowered.api.Game;
import org.spongepowered.api.block.BlockType;
import org.spongepowered.api.command.args.GenericArguments;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.config.DefaultConfig;
import org.spongepowered.api.data.DataView;
import org.spongepowered.api.data.persistence.DataTranslators;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.cause.EventContext;
import org.spongepowered.api.event.filter.Getter;
import org.spongepowered.api.event.game.state.GameInitializationEvent;
import org.spongepowered.api.event.game.state.GamePreInitializationEvent;
import org.spongepowered.api.event.game.state.GameStoppingServerEvent;
import org.spongepowered.api.event.network.ClientConnectionEvent;
import org.spongepowered.api.item.ItemType;
import org.spongepowered.api.item.inventory.ItemStack;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.scheduler.Scheduler;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.service.economy.EconomyService;
import org.spongepowered.api.service.economy.account.UniqueAccount;
import org.spongepowered.api.service.economy.transaction.ResultType;
import org.spongepowered.api.service.economy.transaction.TransactionResult;
import org.spongepowered.api.service.pagination.PaginationList;
import org.spongepowered.api.service.pagination.PaginationService;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.action.TextActions;
import org.spongepowered.api.text.format.TextColors;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Transaction;

import java.io.*;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Plugin(id = "market", name = "Market", description = "Market", url = "https://kookykraftmc.net", authors = {"TimeTheCat"})
public class Market {

    public static Market instance;

    @Inject
    private Logger logger;

    @Inject
    private Game game;

    @Inject
    @DefaultConfig(sharedRoot = false)
    private File defaultCfg;

    private CommentedConfigurationNode cfg;

    @Inject
    @DefaultConfig(sharedRoot = false)
    private ConfigurationLoader<CommentedConfigurationNode> configManager;

    private String serverName;
    private int redisPort;
    private String redisHost;
    private String redisPass;
    private JedisPool jedisPool;

    // Optional MySQL storage service used for cross server synchronization
    private MySqlStorageService sqlStorage;

    // Task used to poll MySQL for cross server events
    private Task sqlListenerTask;

    private Database database;

    // Flag indicating which backend is used for data storage
    private boolean useMySql;

    private Cause marketCause;
    private List<String> blacklistedItems = Lists.newArrayList();

    @Listener
    public void onPreInit(GamePreInitializationEvent event) {
        try {
            if (!defaultCfg.exists()) {
                logger.info("Creating config...");
                defaultCfg.createNewFile();

                this.cfg = getConfigManager().load();

                this.cfg.getNode("Market", "Sponge", "Version").setValue(0.1);

                this.cfg.getNode("Redis", "Host").setValue("localhost");
                this.cfg.getNode("Redis", "Port").setValue(6379);
                this.cfg.getNode("Redis", "Use-password").setValue(false);
                this.cfg.getNode("Redis", "Password").setValue("password");

                this.cfg.getNode("Market", "Sponge", "Server").setValue("TEST");

                // Storage selection and MySQL defaults
                this.cfg.getNode("Storage", "Type")
                        .setComment("Storage backend used by the plugin. Valid options: redis or mysql")
                        .setValue("redis");
                this.cfg.getNode("MySQL").setComment("MySQL connection settings (used when Storage.Type is 'mysql')");
                this.cfg.getNode("MySQL", "Host").setValue("localhost");
                this.cfg.getNode("MySQL", "Port").setValue(3306);
                this.cfg.getNode("MySQL", "Database").setValue("market");
                this.cfg.getNode("MySQL", "Username").setValue("root");
                this.cfg.getNode("MySQL", "Password").setValue("");
                logger.info("Config created...");
                this.getConfigManager().save(cfg);
            }

            this.cfg = this.configManager.load();

            this.serverName = cfg.getNode("Market", "Sponge", "Server").getString();

            String storageType = cfg.getNode("Storage", "Type").getString("redis");
            useMySql = "mysql".equalsIgnoreCase(storageType);

            if (useMySql) {
                String sqlHost = cfg.getNode("MySQL", "Host").getString("localhost");
                int sqlPort = cfg.getNode("MySQL", "Port").getInt(3306);
                String sqlDatabase = cfg.getNode("MySQL", "Database").getString("market");
                String sqlUser = cfg.getNode("MySQL", "Username").getString("root");
                String sqlPassword = cfg.getNode("MySQL", "Password").getString("");
                database = new Database(sqlHost, sqlPort, sqlDatabase, sqlUser, sqlPassword, logger);
                database.runMigrations();
                try {
                    sqlStorage = new MySqlStorageService(database.getDataSource(), logger);
                    subscribe();
                } catch (SQLException e) {
                    logger.error("Failed to initialize MySQL storage service", e);
                }
            } else {
                this.redisPort = cfg.getNode("Redis", "Port").getInt();
                this.redisHost = cfg.getNode("Redis", "Host").getString();
                this.redisPass = cfg.getNode("Redis", "Password").getString();
                if (this.cfg.getNode("Redis", "Use-password").getBoolean()) {
                    jedisPool = setupRedis(this.redisHost, this.redisPort, this.redisPass);
                } else {
                    jedisPool = setupRedis(this.redisHost, this.redisPort);
                }
            }

        } catch (Exception e) {
            logger.error("Failed to initialize Market", e);
        }
    }

    @Listener
    public void onInit(GameInitializationEvent event) {
        instance = this;
        // SpongeAPI 7: use EventContext + plugin instance/container in the Cause
        marketCause = Cause.of(EventContext.empty(), this);

        if (useMySql) {
            if (database != null) {
                try (Connection conn = database.getDataSource().getConnection();
                     PreparedStatement ps = conn.prepareStatement("SELECT item FROM blacklist");
                     ResultSet rs = ps.executeQuery()) {
                    blacklistedItems = new ArrayList<>();
                    while (rs.next()) {
                        blacklistedItems.add(rs.getString("item"));
                    }
                } catch (SQLException e) {
                    logger.error("Failed to load blacklist from MySQL", e);
                }
            } else {
                logger.error("Fatal error: MySQL initialization failed (database is null). Aborting initialization.");
                return;
            }
        } else if (useMySql && database == null) {
            logger.error("MySQL initialization failed (database is null). Check credentials or driver.");
        } else {

            JedisPool pool = getJedis();
            if (pool == null) {
                logger.info("Skipping blacklist loading from Redis; no pool available.");
            } else {
                try (Jedis jedis = pool.getResource()) {
                    blacklistedItems = Lists.newArrayList(jedis.hgetAll(RedisKeys.BLACKLIST).keySet());
                }
            }
        }

        CommandSpec createMarketCmd = CommandSpec.builder()
                .executor(new CreateCommand())
                .arguments(GenericArguments.integer(Text.of("quantity")), GenericArguments.integer(Text.of("price")))
                .permission("market.command.createlisting")
                .description(Text.of("Create a market listing."))
                .build();

        CommandSpec listingsCmd = CommandSpec.builder()
                .executor(new ListingsCommand())
                .permission("market.command.listings")
                .description(Text.of("List all market listings."))
                .build();

        CommandSpec listingInfoCmd = CommandSpec.builder()
                .executor(new ListingInfoCommand())
                .permission("market.command.check")
                .arguments(GenericArguments.string(Text.of("id")))
                .description(Text.of("Get info about a listing."))
                .build();

        CommandSpec buyCmd = CommandSpec.builder()
                .executor(new BuyCommand())
                .permission("market.command.buy")
                .arguments(GenericArguments.string(Text.of("id")))
                .description(Text.of("Buy an Item from the market."))
                .build();

        CommandSpec addStockCmd = CommandSpec.builder()
                .executor(new AddStockCommand())
                .permission("market.command.addstock")
                .arguments(GenericArguments.string(Text.of("id")))
                .description(Text.of("Add more stock to your market listing."))
                .build();

        CommandSpec removeListingCmd = CommandSpec.builder()
                .executor(new RemoveListingCommand())
                .permission("market.command.removelisting")
                .arguments(GenericArguments.string(Text.of("id")))
                .description(Text.of("Remove an item from the market."))
                .build();

        CommandSpec blacklistAddCmd = CommandSpec.builder()
                .executor(new BlacklistAddCommand())
                .permission("market.command.staff.blacklist.add")
                .description(Text.of("Add an item to the market blacklist."))
                .build();

        CommandSpec blacklistRmCmd = CommandSpec.builder()
                .executor(new BlacklistRemoveCommand())
                .permission("market.command.staff.blacklist.remove")
                .description(Text.of("Remove an item to the market blacklist."))
                .arguments(GenericArguments.string(Text.of("id")))
                .build();

        CommandSpec blacklistCmd = CommandSpec.builder()
                .executor(new BlackListCommand())
                .permission("market.command.blacklist")
                .description(Text.of("List all blacklisted items."))
                .child(blacklistAddCmd, "add")
                .child(blacklistRmCmd, "remove")
                .build();

        CommandSpec itemSearch = CommandSpec.builder()
                .executor(new SearchCommand.ItemSearch())
                .permission("market.command.search")
                .arguments(GenericArguments.catalogedElement(Text.of("item"), ItemType.class))
                .description(Text.of("List all market listings for a specific item."))
                .build();

        CommandSpec nameSearch = CommandSpec.builder()
                .executor(new SearchCommand.NameSearch())
                .permission("market.command.search")
                .arguments(GenericArguments.user(Text.of("user")))
                .description(Text.of("List all market listings for a specific name."))
                .build();

        CommandSpec search = CommandSpec.builder()
                .executor(new SearchCommand())
                .permission("market.command.search")
                .description(Text.of("List all search options."))
                .child(itemSearch, "item")
                .child(nameSearch, "name")
                .build();

        CommandSpec marketCmd = CommandSpec.builder()
                .executor(new MarketCommand())
                .permission("market.command.base")
                .description(Text.of("Market base command."))
                .child(createMarketCmd, "create")
                .child(listingsCmd, "listings")
                .child(listingInfoCmd, "check")
                .child(buyCmd, "buy")
                .child(addStockCmd, "addstock")
                .child(removeListingCmd, "removelisting")
                .child(blacklistCmd, "blacklist")
                .child(search, "search")
                .build();
        getGame().getCommandManager().register(this, marketCmd, "market");
    }

    @Listener
    public void onServerStop(GameStoppingServerEvent event) {
        // The Sponge API Task interface no longer exposes an "isCancelled" method
        // in some versions. Calling cancel() on an already-cancelled task is a
        // no-op, so simply check for null before cancelling to avoid compilation
        // issues across API versions.
        if (sqlListenerTask != null) {
            sqlListenerTask.cancel();
            sqlListenerTask = null;
        }
        if (jedisPool != null) {
            jedisPool.close();
            jedisPool = null;
        }
        if (database != null) {
            database.close();
        }
    }

    @Listener
    public void onPlayerJoin(ClientConnectionEvent.Join event, @Getter("getTargetEntity") Player player) {
        updateUUIDCache(player.getUniqueId().toString(), player.getName());
    }

    private void updateUUIDCache(String uuid, String name) {
        if (useMySql) {
            try (Connection conn = database.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement("REPLACE INTO uuid_cache (uuid, name) VALUES (?, ?)")) {
                ps.setString(1, uuid);
                ps.setString(2, name);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to update UUID cache", e);
            }
        } else {
            JedisPool pool = getJedis();
            if (pool == null) {
                return;
            }
            try (Jedis jedis = pool.getResource()) {
                jedis.hset(RedisKeys.UUID_CACHE, uuid, name);
            }
        }
    }

    private String getNameFromUUID(String uuid) {
        if (useMySql) {
            try (Connection conn = database.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT name FROM uuid_cache WHERE uuid = ?")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("name");
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to lookup UUID", e);
            }
            return uuid;
        } else {
            JedisPool pool = getJedis();
            if (pool == null) {
                return uuid;
            }
            try (Jedis jedis = pool.getResource()) {
                String name = jedis.hget(RedisKeys.UUID_CACHE, uuid);
                return name != null ? name : uuid;
            }
        }
    }

    private ConfigurationLoader<CommentedConfigurationNode> getConfigManager() {
        return configManager;
    }

    public MySqlStorageService getMySqlStorageService() {
        return sqlStorage;
    }

    void subscribe() {
        if (sqlStorage != null) {
            sqlListenerTask = getScheduler().createTaskBuilder()
                    .execute(new MySqlListener(this, sqlStorage))
                    .interval(1, TimeUnit.SECONDS)
                    .name("Market SQL Listener")
                    .submit(this);
        }
    }

    //////////////////////////////// REDIS ////////////////////////////////
    private JedisPool setupRedis(String host, int port) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(128);
        return new JedisPool(config, host, port, 0);
    }

    private JedisPool setupRedis(String host, int port, String password) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(128);
        return new JedisPool(config, host, port, 0, password);
    }

    public JedisPool getJedis() {
        if (cfg == null) {
            logger.error("Configuration not loaded. Unable to provide Jedis pool.");
            return null;
        }

        if (useMySql) {
            logger.info("MySQL mode active; Redis operations are disabled.");
            return null;
        }

        if (jedisPool == null) {
            // Use the same case as onPreInit ("Redis")
            if (this.cfg.getNode("Redis", "Use-password").getBoolean()) {
                jedisPool = setupRedis(this.redisHost, this.redisPort, this.redisPass);
            } else {
                jedisPool = setupRedis(this.redisHost, this.redisPort);
            }
        }

        return jedisPool;
    }

    public PaginationService getPaginationService() {
        return game.getServiceManager().provide(PaginationService.class).get();
    }

    public String getServerName() {
        return serverName;
    }

    private Game getGame() {
        return game;
    }

    public List<Text> getCommands() {
        List<Text> commands = new ArrayList<>();
        commands.add(Text.builder()
                .onHover(TextActions.showText(Text.of("Show the items in the market.")))
                .onClick(TextActions.suggestCommand("/market listings"))
                .append(Text.of("/market listings"))
                .build());
        commands.add(Text.builder()
                .onHover(TextActions.showText(Text.of("Add the item in your hand to the market.")))
                .onClick(TextActions.suggestCommand("/market create <quantity> <price>"))
                .append(Text.of("/market create <quantity> <price>"))
                .build());
        commands.add(Text.builder()
                .onHover(TextActions.showText(Text.of("Get info about a listing.")))
                .onClick(TextActions.suggestCommand("/market check <id>"))
                .append(Text.of("/market check <id>"))
                .build());
        commands.add(Text.builder()
                .onHover(TextActions.showText(Text.of("Buy an item from the market.")))
                .onClick(TextActions.suggestCommand("/market buy <id>"))
                .append(Text.of("/market buy <id>"))
                .build());
        commands.add(Text.builder()
                .onHover(TextActions.showText(Text.of("Add more stock to your listing.")))
                .onClick(TextActions.suggestCommand("/market addstock <id>"))
                .append(Text.of("/market addstock <id>"))
                .build());
        commands.add(Text.builder()
                .onHover(TextActions.showText(Text.of("Search the market for a playername or item id.")))
                .onClick(TextActions.suggestCommand("/market search <name|item>"))
                .append(Text.of("/market search <name|item>"))
                .build());
        commands.add(Text.builder()
                .onHover(TextActions.showText(Text.of("Remove a listing from the market.")))
                .onClick(TextActions.suggestCommand("/market removelisting <id>"))
                .append(Text.of("/market removelisting <id>"))
                .build());
        return commands;
    }

    private String serializeItem(ItemStack itemStack) {
        ConfigurationNode node = DataTranslators.CONFIGURATION_NODE.translate(itemStack.toContainer());
        StringWriter stringWriter = new StringWriter();
        try {
            HoconConfigurationLoader.builder().setSink(() -> new BufferedWriter(stringWriter)).build().save(node);
        } catch (IOException e) {
            logger.error("Failed to serialize item", e);
        }
        return stringWriter.toString();
    }

    private Optional<ItemStack> deserializeItemStack(String item) {
        ConfigurationNode node = null;
        try {
            node = HoconConfigurationLoader.builder().setSource(() -> new BufferedReader(new StringReader(item))).build().load();
        } catch (IOException e) {
            logger.error("Failed to deserialize item stack", e);
        }
        DataView dataView = DataTranslators.CONFIGURATION_NODE.translate(node);
        return getGame().getDataManager().deserialize(ItemStack.class, dataView);
    }

    public int addListing(Player player, ItemStack itemStack, int quantityPerSale, int price) {
        if (useMySql) {
            if (itemStack.getQuantity() < quantityPerSale || quantityPerSale <= 0 || isBlacklisted(itemStack)) {
                return 0;
            }
            try (Connection conn = database.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "INSERT INTO listings (seller_uuid, item, stock, price, quantity) VALUES (?, ?, ?, ?, ?)",
                         Statement.RETURN_GENERATED_KEYS)) {
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
                logger.error("Failed to add listing", e);
            }
            return 0;
        } else {
            JedisPool pool = getJedis();
            if (pool == null) {
                return 0;
            }
            try (Jedis jedis = pool.getResource()) {
                // if there are fewer items than they want to sell every time, return 0
                if (itemStack.getQuantity() < quantityPerSale || quantityPerSale <= 0 || isBlacklisted(itemStack)) return 0;
                if (!jedis.exists(RedisKeys.lastMarketId())) {
                    jedis.set(RedisKeys.lastMarketId(), String.valueOf(1));
                    int id = 1;
                    String key = RedisKeys.marketItemKey(String.valueOf(id));
                    Transaction m = jedis.multi();
                    m.hset(key, "Item", serializeItem(itemStack));
                    m.hset(key, "Seller", player.getUniqueId().toString());
                    m.hset(key, "Stock", String.valueOf(itemStack.getQuantity()));
                    m.hset(key, "Price", String.valueOf(price));
                    m.hset(key, "Quantity", String.valueOf(quantityPerSale));
                    m.exec();

                    jedis.hset(RedisKeys.forSale(), String.valueOf(id), player.getUniqueId().toString());

                    jedis.incr(RedisKeys.lastMarketId());

                    return id;
                } else {
                    int id = Integer.parseInt(jedis.get(RedisKeys.lastMarketId()));
                    String key = RedisKeys.marketItemKey(String.valueOf(id));
                    if (checkForOtherListings(itemStack, player.getUniqueId().toString())) return -1;

                    Transaction m = jedis.multi();
                    m.hset(key, "Item", serializeItem(itemStack));
                    m.hset(key, "Seller", player.getUniqueId().toString());
                    m.hset(key, "Stock", String.valueOf(itemStack.getQuantity()));
                    m.hset(key, "Price", String.valueOf(price));
                    m.hset(key, "Quantity", String.valueOf(quantityPerSale));
                    m.exec();

                    jedis.hset(RedisKeys.forSale(), String.valueOf(id), player.getUniqueId().toString());

                    jedis.incr(RedisKeys.lastMarketId());

                    return id;
                }
            }
        }
    }

    private boolean checkForOtherListings(ItemStack itemStack, String s) {
        if (useMySql) {
            try (Connection conn = database.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT item FROM listings WHERE seller_uuid = ?")) {
                ps.setString(1, s);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Optional<ItemStack> existing = deserializeItemStack(rs.getString("item"));
                        if (existing.isPresent() && matchItemStacks(existing.get(), itemStack)) {
                            return true;
                        }
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to check listings", e);
            }
            return false;
        } else {
            JedisPool pool = getJedis();
            if (pool == null) {
                return false;
            }
            try (Jedis jedis = pool.getResource()) {
                Map<String, String> d = jedis.hgetAll(RedisKeys.forSale());

                Map<String, String> e = d.entrySet().stream()
                        .filter(stringStringEntry -> stringStringEntry.getValue().equals(s))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                if (e.size() == 0) return false;
                else {
                    final boolean[] hasOther = {false};
                    e.forEach((s1, s2) -> {
                        Optional<ItemStack> ooi = deserializeItemStack(jedis.hget(RedisKeys.marketItemKey(s1), "Item"));
                        if (!ooi.isPresent()) return;
                        if (matchItemStacks(ooi.get(), itemStack)) {
                            hasOther[0] = true;
                        }
                    });
                    return hasOther[0];
                }
            }
        }
    }

    public PaginationList getListings() {
        if (useMySql) {
            List<Text> texts = new ArrayList<>();
            try (Connection conn = database.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT id, seller_uuid, item, price, quantity FROM listings");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Text.Builder l = Text.builder();
                    Optional<ItemStack> is = deserializeItemStack(rs.getString("item"));
                    if (!is.isPresent()) continue;
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
                    l.append(Text.of(TextColors.LIGHT_PURPLE, " " + getNameFromUUID(rs.getString("seller_uuid"))));
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
                logger.error("Failed to get listings", e);
            }
            return getPaginationService().builder().contents(texts).title(Texts.MARKET_LISTINGS).build();
        } else {
            JedisPool pool = getJedis();
            if (pool == null) {
                return getPaginationService().builder().contents(Collections.emptyList()).title(Texts.MARKET_LISTINGS).build();
            }
            try (Jedis jedis = pool.getResource()) {
                Set<String> openListings = jedis.hgetAll(RedisKeys.forSale()).keySet();
                List<Text> texts = new ArrayList<>();
                for (String openListing : openListings) {
                    Map<String, String> listing = jedis.hgetAll(RedisKeys.marketItemKey(openListing));
                    Text.Builder l = Text.builder();
                    Optional<ItemStack> is = deserializeItemStack(listing.get("Item"));
                    if (!is.isPresent()) continue;
                    l.append(Texts.quickItemFormat(is.get()));
                    l.append(Text.of(" "));
                    l.append(Text.of(TextColors.WHITE, "@"));
                    l.append(Text.of(" "));
                    l.append(Text.of(TextColors.GREEN, "$" + listing.get("Price")));
                    l.append(Text.of(" "));
                    l.append(Text.of(TextColors.WHITE, "for"));
                    l.append(Text.of(" "));
                    l.append(Text.of(TextColors.GREEN, listing.get("Quantity") + "x"));
                    l.append(Text.of(" "));
                    l.append(Text.of(TextColors.WHITE, "Seller:"));
                    l.append(Text.of(TextColors.LIGHT_PURPLE, " " + jedis.hget(RedisKeys.UUID_CACHE, listing.get("Seller"))));
                    l.append(Text.of(" "));
                    l.append(Text.builder()
                            .color(TextColors.GREEN)
                            .onClick(TextActions.runCommand("/market check " + openListing))
                            .append(Text.of("[Info]"))
                            .onHover(TextActions.showText(Text.of("View more info about this listing.")))
                            .build());

                    texts.add(l.build());
                }
                return getPaginationService().builder().contents(texts).title(Texts.MARKET_LISTINGS).build();
            }
        }
    }

    public List<ItemStack> removeListing(String id, String uuid, boolean staff) {
        if (useMySql) {
            try (Connection conn = database.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT seller_uuid, item, stock FROM listings WHERE id = ?")) {
                ps.setInt(1, Integer.parseInt(id));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    String seller = rs.getString("seller_uuid");
                    if (!seller.equals(uuid) && !staff) return null;
                    int inStock = rs.getInt("stock");
                    Optional<ItemStack> item = deserializeItemStack(rs.getString("item"));
                    if (!item.isPresent()) return null;
                    ItemStack listingIS = item.get();
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
                    try (PreparedStatement del = conn.prepareStatement("DELETE FROM listings WHERE id = ?")) {
                        del.setInt(1, Integer.parseInt(id));
                        del.executeUpdate();
                    }
                    return stacks;
                }
            } catch (SQLException e) {
                logger.error("Failed to remove listing", e);
            }
            return null;
        } else {
            JedisPool pool = getJedis();
            if (pool == null) {
                return null;
            }
            try (Jedis jedis = pool.getResource()) {
                if (!jedis.hexists(RedisKeys.forSale(), id)) return null;
                else {
                    // get info about the listing
                    Map<String, String> listing = jedis.hgetAll(RedisKeys.marketItemKey(id));
                    // check to see if the uuid matches the seller, or the user is a staff member
                    if (!listing.get("Seller").equals(uuid) && !staff) return null;
                    // get how much stock it has
                    int inStock = Integer.parseInt(listing.get("Stock"));
                    // deserialize the item
                    ItemStack listingIS = deserializeItemStack(listing.get("Item")).get();
                    // calculate the amount of stacks to make
                    int stacksInStock = inStock / listingIS.getMaxStackQuantity();
                    // new list for stacks
                    List<ItemStack> stacks = new ArrayList<>();
                    // until all stacks are pulled out, keep adding more stacks to stacks
                    for (int i = 0; i < stacksInStock; i++) {
                        stacks.add(listingIS.copy());
                    }
                    if (inStock % listingIS.getMaxStackQuantity() != 0) {
                        ItemStack extra = listingIS.copy();
                        extra.setQuantity(inStock % listingIS.getMaxStackQuantity());
                        stacks.add(extra);
                    }
                    // remove from the listings
                    jedis.hdel(RedisKeys.forSale(), id);
                    return stacks;
                }
            }
        }
    }

    public PaginationList getListing(String id) {
        if (useMySql) {
            try (Connection conn = database.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT seller_uuid, item, stock, price, quantity FROM listings WHERE id = ?")) {
                ps.setInt(1, Integer.parseInt(id));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    List<Text> texts = new ArrayList<>();
                    texts.add(Texts.quickItemFormat(deserializeItemStack(rs.getString("item")).get()));
                    texts.add(Text.of("Seller: " + getNameFromUUID(rs.getString("seller_uuid"))));
                    texts.add(Text.of("Price: " + rs.getInt("price")));
                    texts.add(Text.of("Quantity: " + rs.getInt("quantity")));
                    texts.add(Text.of("Stock: " + rs.getInt("stock")));
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
                    return getPaginationService().builder().title(Texts.MARKET_LISTING(id)).contents(texts).build();
                }
            } catch (SQLException e) {
                logger.error("Failed to get listing", e);
            }
            return null;
        } else {
            JedisPool pool = getJedis();
            if (pool == null) {
                return null;
            }
            try (Jedis jedis = pool.getResource()) {
                // if the item is not for sale, do not get the listing
                if (!jedis.hexists(RedisKeys.forSale(), id)) return null;
                // get info about the listing
                Map<String, String> listing = jedis.hgetAll(RedisKeys.marketItemKey(id));
                // create list of Texts for pages
                List<Text> texts = new ArrayList<>();
                // replace with item if key is "Item", replace uuid with name from cache.
                listing.forEach((key, value) -> {
                    switch (key) {
                        case "Item":
                            texts.add(Texts.quickItemFormat(deserializeItemStack(value).get()));
                            break;
                        case "Seller":
                            texts.add(Text.of("Seller: " + jedis.hget(RedisKeys.UUID_CACHE, value)));
                            break;
                        default:
                            texts.add(Text.of(key + ": " + value));
                            break;
                    }
                });

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

                return getPaginationService().builder().title(Texts.MARKET_LISTING(id)).contents(texts).build();
            }
        }
    }

    public boolean addStock(ItemStack itemStack, String id, UUID uuid) {
        if (useMySql) {
            try (Connection conn = database.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT seller_uuid, item, stock FROM listings WHERE id = ?")) {
                ps.setInt(1, Integer.parseInt(id));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return false;
                    if (!rs.getString("seller_uuid").equals(uuid.toString())) return false;
                    ItemStack listingStack = deserializeItemStack(rs.getString("item")).get();
                    if (matchItemStacks(listingStack, itemStack)) {
                        int stock = rs.getInt("stock");
                        int quan = itemStack.getQuantity() + stock;
                        try (PreparedStatement upd = conn.prepareStatement("UPDATE listings SET stock = ? WHERE id = ?")) {
                            upd.setInt(1, quan);
                            upd.setInt(2, Integer.parseInt(id));
                            upd.executeUpdate();
                        }
                        return true;
                    } else {
                        return false;
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to add stock", e);
            }
            return false;
        } else {
            JedisPool pool = getJedis();
            if (pool == null) {
                return false;
            }
            try (Jedis jedis = pool.getResource()) {
                if (!jedis.hexists(RedisKeys.forSale(), id)) return false;
                else if (!jedis.hget(RedisKeys.marketItemKey(id), "Seller").equals(uuid.toString())) return false;
                else {
                    ItemStack listingStack = deserializeItemStack(jedis.hget(RedisKeys.marketItemKey(id), "Item")).get();
                    // if the stack in the listing matches the stack it's trying to add, add it to the stack
                    if (matchItemStacks(listingStack, itemStack)) {
                        int stock = Integer.parseInt(jedis.hget(RedisKeys.marketItemKey(id), "Stock"));
                        int quan = itemStack.getQuantity() + stock;
                        jedis.hset(RedisKeys.marketItemKey(id), "Stock", String.valueOf(quan));
                        return true;
                    } else return false;
                }
            }
        }
    }

    private boolean matchItemStacks(ItemStack is0, ItemStack is1) {
        return new DataComparator().compare(is0, is1) == 0;
    }

    public ItemStack purchase(UniqueAccount uniqueAccount, String id) {
        if (useMySql) {
            try (Connection conn = database.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT seller_uuid, item, price, quantity, stock FROM listings WHERE id = ?")) {
                ps.setInt(1, Integer.parseInt(id));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    TransactionResult tr = uniqueAccount.transfer(
                            getEconomyService().getOrCreateAccount(UUID.fromString(rs.getString("seller_uuid"))).get(),
                            getEconomyService().getDefaultCurrency(),
                            BigDecimal.valueOf(rs.getInt("price")),
                            marketCause);
                    if (tr.getResult().equals(ResultType.SUCCESS)) {
                        ItemStack is = deserializeItemStack(rs.getString("item")).get();
                        int quant = rs.getInt("quantity");
                        int inStock = rs.getInt("stock");
                        int newQuant = inStock - quant;
                        if (newQuant < quant) {
                            try (PreparedStatement del = conn.prepareStatement("DELETE FROM listings WHERE id = ?")) {
                                del.setInt(1, Integer.parseInt(id));
                                del.executeUpdate();
                            }
                        } else {
                            try (PreparedStatement upd = conn.prepareStatement("UPDATE listings SET stock = ? WHERE id = ?")) {
                                upd.setInt(1, newQuant);
                                upd.setInt(2, Integer.parseInt(id));
                                upd.executeUpdate();
                            }
                        }
                        ItemStack nis = is.copy();
                        nis.setQuantity(quant);
                        return nis;
                    } else {
                        return null;
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to purchase listing", e);
            }
            return null;
        } else {
            JedisPool pool = getJedis();
            if (pool == null) {
                return null;
            }
            try (Jedis jedis = pool.getResource()) {
                if (!jedis.hexists(RedisKeys.forSale(), id)) return null;
                else {
                    TransactionResult tr = uniqueAccount.transfer(
                            getEconomyService().getOrCreateAccount(UUID.fromString(jedis.hget(RedisKeys.marketItemKey(id), "Seller"))).get(),
                            getEconomyService().getDefaultCurrency(),
                            BigDecimal.valueOf(Long.parseLong(jedis.hget(RedisKeys.marketItemKey(id), "Price"))),
                            marketCause // SpongeAPI 7: pass the Cause directly
                    );
                    if (tr.getResult().equals(ResultType.SUCCESS)) {
                        // get the itemstack
                        ItemStack is = deserializeItemStack(jedis.hget(RedisKeys.marketItemKey(id), "Item")).get();
                        // get the quantity per sale
                        int quant = Integer.parseInt(jedis.hget(RedisKeys.marketItemKey(id), "Quantity"));
                        // get the amount in stock
                        int inStock = Integer.parseInt(jedis.hget(RedisKeys.marketItemKey(id), "Stock"));
                        // get the new quantity
                        int newQuant = inStock - quant;
                        // if the new quantity is less than the quantity to be sold, expire the listing
                        if (newQuant < quant) {
                            jedis.hdel(RedisKeys.forSale(), id);
                        } else {
                            jedis.hset(RedisKeys.marketItemKey(id), "Stock", String.valueOf(newQuant));
                        }
                        ItemStack nis = is.copy();
                        nis.setQuantity(quant);
                        return nis;
                    } else {
                        return null;
                    }
                }
            }
        }
    }

    public EconomyService getEconomyService() {
        return game.getServiceManager().provide(EconomyService.class).get();
    }

    public boolean blacklistAddCmd(String id) {
        if (useMySql) {
            try (Connection conn = database.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement("INSERT IGNORE INTO blacklist(item) VALUES (?)")) {
                ps.setString(1, id);
                int rows = ps.executeUpdate();
                if (rows == 0) return false;
            } catch (SQLException e) {
                logger.error("Failed to add blacklist entry", e);
                return false;
            }
        } else {
            JedisPool pool = getJedis();
            if (pool == null) {
                return false;
            }
            try (Jedis jedis = pool.getResource()) {
                if (jedis.hexists(RedisKeys.BLACKLIST, id)) return false;
                jedis.hset(RedisKeys.BLACKLIST, id, String.valueOf(true));
            }
        }
        addIDToBlackList(id);
        return true;
    }

    public boolean blacklistRemoveCmd(String id) {
        if (useMySql) {
            try (Connection conn = database.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM blacklist WHERE item = ?")) {
                ps.setString(1, id);
                int rows = ps.executeUpdate();
                if (rows == 0) return false;
            } catch (SQLException e) {
                logger.error("Failed to remove blacklist entry", e);
                return false;
            }
        } else {
            JedisPool pool = getJedis();
            if (pool == null) {
                return false;
            }
            try (Jedis jedis = pool.getResource()) {
                if (!jedis.hexists(RedisKeys.BLACKLIST, id)) return false;
                jedis.hdel(RedisKeys.BLACKLIST, id);
            }
        }
        rmIDFromBlackList(id);
        return true;
    }

    public void addIDToBlackList(String id) {
        blacklistedItems.add(id);
    }

    private List<String> getBlacklistedItems() {
        return blacklistedItems;
    }

    private boolean isBlacklisted(ItemStack itemStack) {
        Optional<BlockType> type = itemStack.getItem().getBlock();
        String id = type.map(blockType -> blockType.getDefaultState().getId())
                .orElseGet(() -> itemStack.getItem().getId());
        return blacklistedItems.contains(id);
    }

    public PaginationList getBlacklistedItemList() {
        List<Text> texts = new ArrayList<>();
        for (String blacklistedItem : getBlacklistedItems()) {
            texts.add(Text.of(blacklistedItem));
        }
        return getPaginationService().builder().contents(texts).title(Text.of(TextColors.GREEN, "Market Blacklist")).build();
    }

    public void rmIDFromBlackList(String message) {
        blacklistedItems.remove(message);
    }

    public PaginationList searchForItem(ItemType itemType) {
        List<Text> texts = new ArrayList<>();
        if (useMySql) {
            try (Connection conn = database.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT id, seller_uuid, item, price, quantity FROM listings");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Optional<ItemStack> is = deserializeItemStack(rs.getString("item"));
                    if (!is.isPresent() || !is.get().getItem().equals(itemType)) continue;
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
                    l.append(Text.of(TextColors.LIGHT_PURPLE, " " + getNameFromUUID(rs.getString("seller_uuid"))));
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
                logger.error("Failed to search listings", e);
            }
        } else {
            JedisPool pool = getJedis();
            if (pool != null) {
                try (Jedis jedis = pool.getResource()) {
                    Set<String> openListings = jedis.hgetAll(RedisKeys.forSale()).keySet();
                    for (String openListing : openListings) {
                        Map<String, String> listing = jedis.hgetAll(RedisKeys.marketItemKey(openListing));
                        Text.Builder l = Text.builder();
                        Optional<ItemStack> is = deserializeItemStack(listing.get("Item"));
                        if (!is.isPresent()) continue;
                        if (is.get().getItem().equals(itemType)) {
                            l.append(Texts.quickItemFormat(is.get()));
                            l.append(Text.of(" "));
                            l.append(Text.of(TextColors.WHITE, "@"));
                            l.append(Text.of(" "));
                            l.append(Text.of(TextColors.GREEN, "$" + listing.get("Price")));
                            l.append(Text.of(" "));
                            l.append(Text.of(TextColors.WHITE, "for"));
                            l.append(Text.of(" "));
                            l.append(Text.of(TextColors.GREEN, listing.get("Quantity") + "x"));
                            l.append(Text.of(" "));
                            l.append(Text.of(TextColors.WHITE, "Seller:"));
                            l.append(Text.of(TextColors.LIGHT_PURPLE, " " + jedis.hget(RedisKeys.UUID_CACHE, listing.get("Seller"))));
                            l.append(Text.of(" "));
                            l.append(Text.builder()
                                    .color(TextColors.GREEN)
                                    .onClick(TextActions.runCommand("/market check " + openListing))
                                    .append(Text.of("[Info]"))
                                    .onHover(TextActions.showText(Text.of("View more info about this listing.")))
                                    .build());
                            texts.add(l.build());
                        }
                    }
                }
            }
        }
        if (texts.size() == 0) texts.add(Text.of(TextColors.RED, "No listings found."));
        return getPaginationService().builder().contents(texts).title(Texts.MARKET_SEARCH).build();
    }

    public PaginationList searchForUUID(UUID uniqueId) {
        List<Text> texts = new ArrayList<>();
        if (useMySql) {
            try (Connection conn = database.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT id, seller_uuid, item, price, quantity FROM listings WHERE seller_uuid = ?")) {
                ps.setString(1, uniqueId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Text.Builder l = Text.builder();
                        Optional<ItemStack> is = deserializeItemStack(rs.getString("item"));
                        if (!is.isPresent()) continue;
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
                        l.append(Text.of(TextColors.LIGHT_PURPLE, " " + getNameFromUUID(rs.getString("seller_uuid"))));
                        l.append(Text.of(" "));
                        l.append(Text.builder()
                                .color(TextColors.GREEN)
                                .onClick(TextActions.runCommand("/market check " + rs.getInt("id")))
                                .append(Text.of("[Info]"))
                                .onHover(TextActions.showText(Text.of("View more info about this listing.")))
                                .build());
                        texts.add(l.build());
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to search listings", e);
            }
        } else {
            JedisPool pool = getJedis();
            if (pool != null) {
                try (Jedis jedis = pool.getResource()) {
                    Set<String> openListings = jedis.hgetAll(RedisKeys.forSale()).keySet();
                    for (String openListing : openListings) {
                        Map<String, String> listing = jedis.hgetAll(RedisKeys.marketItemKey(openListing));
                        if (listing.get("Seller").equals(uniqueId.toString())) {
                            Text.Builder l = Text.builder();
                            Optional<ItemStack> is = deserializeItemStack(listing.get("Item"));
                            if (!is.isPresent()) continue;
                            l.append(Texts.quickItemFormat(is.get()));
                            l.append(Text.of(" "));
                            l.append(Text.of(TextColors.WHITE, "@"));
                            l.append(Text.of(" "));
                            l.append(Text.of(TextColors.GREEN, "$" + listing.get("Price")));
                            l.append(Text.of(" "));
                            l.append(Text.of(TextColors.WHITE, "for"));
                            l.append(Text.of(" "));
                            l.append(Text.of(TextColors.GREEN, listing.get("Quantity") + "x"));
                            l.append(Text.of(" "));
                            l.append(Text.of(TextColors.WHITE, "Seller:"));
                            l.append(Text.of(TextColors.LIGHT_PURPLE, " " + jedis.hget(RedisKeys.UUID_CACHE, listing.get("Seller"))));
                            l.append(Text.of(" "));
                            l.append(Text.builder()
                                    .color(TextColors.GREEN)
                                    .onClick(TextActions.runCommand("/market check " + openListing))
                                    .append(Text.of("[Info]"))
                                    .onHover(TextActions.showText(Text.of("View more info about this listing.")))
                                    .build());
                            texts.add(l.build());
                        }
                    }
                }
            }
        }
        if (texts.size() == 0) texts.add(Text.of(TextColors.RED, "No listings found."));
        return getPaginationService().builder().contents(texts).title(Texts.MARKET_SEARCH).build();
    }

    public Scheduler getScheduler() {
        return game.getScheduler();
    }
}
