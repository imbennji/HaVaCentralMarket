package com.kookykraftmc.market;

import com.google.inject.Inject;
import com.kookykraftmc.market.commands.MarketCommand;
import com.kookykraftmc.market.commands.subcommands.*;
import com.kookykraftmc.market.commands.subcommands.blacklist.BlacklistAddCommand;
import com.kookykraftmc.market.commands.subcommands.blacklist.BlacklistRemoveCommand;
import com.kookykraftmc.market.DataComparator;
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
import org.spongepowered.api.service.economy.EconomyService;
import org.spongepowered.api.service.economy.account.UniqueAccount;
import org.spongepowered.api.service.pagination.PaginationList;
import org.spongepowered.api.service.pagination.PaginationService;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.action.TextActions;
import org.spongepowered.api.text.format.TextColors;
import com.kookykraftmc.market.storage.StorageService;
import com.kookykraftmc.market.storage.RedisStorageService;
import com.kookykraftmc.market.storage.MySqlStorageService;

import java.io.*;
import java.util.*;

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

    private ConfigurationNode cfg;

    @Inject
    @DefaultConfig(sharedRoot = false)
    private ConfigurationLoader<CommentedConfigurationNode> configManager;

    private String serverName;
    private int redisPort;
    private String redisHost;
    private String redisPass;

    private StorageService storageService;

    private Cause marketCause;
    private List<String> blacklistedItems;

    @Listener
    public void onPreInit(GamePreInitializationEvent event) {
        try {
            if (!defaultCfg.exists()) {
                logger.info("Creating config...");
                defaultCfg.createNewFile();

                this.cfg = getConfigManager().load();

                this.cfg.getNode("market", "sponge", "version").setValue(0.1);

                ConfigurationNode storage = this.cfg.getNode("storage");
                storage.getNode("type").setValue("redis");

                ConfigurationNode redis = storage.getNode("redis");
                redis.getNode("host").setValue("localhost");
                redis.getNode("port").setValue(6379);
                redis.getNode("use-password").setValue(false);
                redis.getNode("password").setValue("password");

                ConfigurationNode mysql = storage.getNode("mysql");
                mysql.getNode("host").setValue("localhost");
                mysql.getNode("port").setValue(3306);
                mysql.getNode("database").setValue("market");
                mysql.getNode("user").setValue("root");
                mysql.getNode("password").setValue("password");

                this.cfg.getNode("market", "sponge", "server").setValue("TEST");
                logger.info("Config created...");
                this.getConfigManager().save(cfg);
            }

            this.cfg = this.configManager.load();

            this.serverName = cfg.getNode("market", "sponge", "server").getString();

            ConfigurationNode storage = cfg.getNode("storage");
            String storageType = storage.getNode("type").getString("redis");
            if (storageType.equalsIgnoreCase("mysql")) {
                ConfigurationNode mysql = storage.getNode("mysql");
                String host = mysql.getNode("host").getString("localhost");
                int port = mysql.getNode("port").getInt(3306);
                String database = mysql.getNode("database").getString("market");
                String user = mysql.getNode("user").getString("root");
                String pass = mysql.getNode("password").getString("");
                storageService = new MySqlStorageService(this, host, port, database, user, pass);
            } else {
                ConfigurationNode redis = storage.getNode("redis");
                this.redisPort = redis.getNode("port").getInt(6379);
                this.redisHost = redis.getNode("host").getString("localhost");
                this.redisPass = redis.getNode("password").getString("password");
                boolean usePassword = redis.getNode("use-password").getBoolean(false);
                storageService = new RedisStorageService(this, this.redisHost, this.redisPort, this.redisPass, usePassword);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Listener
    public void onInit(GameInitializationEvent event) {
        instance = this;
        // SpongeAPI 7: use EventContext + plugin instance/container in the Cause
        marketCause = Cause.of(EventContext.empty(), this);

        blacklistedItems = storageService.loadBlacklist();

        subscribe();

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
        if (storageService != null) {
            storageService.close();
        }
    }

    @Listener
    public void onPlayerJoin(ClientConnectionEvent.Join event, @Getter("getTargetEntity") Player player) {
        updateUUIDCache(player.getUniqueId().toString(), player.getName());
    }

    private void updateUUIDCache(String uuid, String name) {
        storageService.cacheUUID(uuid, name);
    }

    private ConfigurationLoader<CommentedConfigurationNode> getConfigManager() {
        return configManager;
    }

    void subscribe() {
        if (storageService instanceof RedisStorageService) {
            ((RedisStorageService) storageService).subscribe();
        }
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

    public String serializeItem(ItemStack itemStack) {
        ConfigurationNode node = DataTranslators.CONFIGURATION_NODE.translate(itemStack.toContainer());
        StringWriter stringWriter = new StringWriter();
        try {
            HoconConfigurationLoader.builder().setSink(() -> new BufferedWriter(stringWriter)).build().save(node);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return stringWriter.toString();
    }

    public Optional<ItemStack> deserializeItemStack(String item) {
        ConfigurationNode node = null;
        try {
            node = HoconConfigurationLoader.builder().setSource(() -> new BufferedReader(new StringReader(item))).build().load();
        } catch (IOException e) {
            e.printStackTrace();
        }
        DataView dataView = DataTranslators.CONFIGURATION_NODE.translate(node);
        return getGame().getDataManager().deserialize(ItemStack.class, dataView);
    }

    public int addListing(Player player, ItemStack itemStack, int quantityPerSale, int price) {
        return storageService.addListing(player, itemStack, quantityPerSale, price);
    }

    public PaginationList getListings() {
        return storageService.getListings();
    }

    public List<ItemStack> removeListing(String id, String uuid, boolean staff) {
        return storageService.removeListing(id, uuid, staff);
    }

    public PaginationList getListing(String id) {
        return storageService.getListing(id);
    }

    public boolean addStock(ItemStack itemStack, String id, UUID uuid) {
        return storageService.addStock(itemStack, id, uuid);
    }

    public boolean matchItemStacks(ItemStack is0, ItemStack is1) {
        return new DataComparator().compare(is0, is1) == 0;
    }

    public ItemStack purchase(UniqueAccount uniqueAccount, String id) {
        return storageService.purchase(uniqueAccount, id);
    }

    public EconomyService getEconomyService() {
        return game.getServiceManager().provide(EconomyService.class).get();
    }

    public Cause getMarketCause() {
        return marketCause;
    }

    public boolean blacklistAddCmd(String id) {
        boolean result = storageService.blacklistAdd(id);
        if (result) addIDToBlackList(id);
        return result;
    }

    public boolean blacklistRemoveCmd(String id) {
        boolean result = storageService.blacklistRemove(id);
        if (result) rmIDFromBlackList(id);
        return result;
    }

    public void addIDToBlackList(String id) {
        blacklistedItems.add(id);
    }

    private List<String> getBlacklistedItems() {
        return blacklistedItems;
    }

    public boolean isBlacklisted(ItemStack itemStack) {
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
        return storageService.searchForItem(itemType);
    }

    public PaginationList searchForUUID(UUID uniqueId) {
        return storageService.searchForUUID(uniqueId);
    }

    public Scheduler getScheduler() {
        return game.getScheduler();
    }
}
