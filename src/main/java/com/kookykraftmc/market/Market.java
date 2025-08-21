package com.kookykraftmc.market;

import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.kookykraftmc.market.commands.MarketCommand;
import com.kookykraftmc.market.commands.subcommands.*;
import com.kookykraftmc.market.commands.subcommands.blacklist.BlacklistAddCommand;
import com.kookykraftmc.market.commands.subcommands.blacklist.BlacklistRemoveCommand;
import com.kookykraftmc.market.sql.Database;
import com.kookykraftmc.market.storage.*;
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
import org.spongepowered.api.service.economy.transaction.ResultType;
import org.spongepowered.api.service.economy.transaction.TransactionResult;
import org.spongepowered.api.service.pagination.PaginationList;
import org.spongepowered.api.service.pagination.PaginationService;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.action.TextActions;
import org.spongepowered.api.text.format.TextColors;

import java.io.*;
import java.math.BigDecimal;
import java.util.*;
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

    private ConfigurationNode cfg;

    @Inject
    @DefaultConfig(sharedRoot = false)
    private ConfigurationLoader<CommentedConfigurationNode> configManager;

    private String serverName;
    private int redisPort;
    private String redisHost;
    private String redisPass;

    private StorageService storage;
    private Database database;

    private Cause marketCause;
    private List<String> blacklistedItems;

    @Listener
    public void onPreInit(GamePreInitializationEvent event) {
        try {
            if (!defaultCfg.exists()) {
                logger.info("Creating config...");
                defaultCfg.createNewFile();

                this.cfg = getConfigManager().load();

                this.cfg.getNode("Market", "Sponge", "Version").setValue(0.1);
                this.cfg.getNode("Market", "Sponge", "Server").setValue("TEST");

                this.cfg.getNode("Storage", "Type").setValue("redis");

                this.cfg.getNode("Redis", "Host").setValue("localhost");
                this.cfg.getNode("Redis", "Port").setValue(6379);
                this.cfg.getNode("Redis", "Use-password").setValue(false);
                this.cfg.getNode("Redis", "Password").setValue("password");

                // MySQL defaults
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
            if ("mysql".equalsIgnoreCase(storageType)) {
                String sqlHost = cfg.getNode("MySQL", "Host").getString("localhost");
                int sqlPort = cfg.getNode("MySQL", "Port").getInt(3306);
                String sqlDatabase = cfg.getNode("MySQL", "Database").getString("market");
                String sqlUser = cfg.getNode("MySQL", "Username").getString("root");
                String sqlPassword = cfg.getNode("MySQL", "Password").getString("");
                database = new Database(sqlHost, sqlPort, sqlDatabase, sqlUser, sqlPassword, logger);
                database.runMigrations();
                storage = new MySqlStorageService(database.getDataSource());
            } else {
                this.redisPort = cfg.getNode("Redis", "Port").getInt();
                this.redisHost = cfg.getNode("Redis", "Host").getString();
                this.redisPass = cfg.getNode("Redis", "Password").getString();
                boolean usePass = cfg.getNode("Redis", "Use-password").getBoolean();
                storage = new RedisStorageService(redisHost, redisPort, usePass ? redisPass : null, serverName);
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

        blacklistedItems = Lists.newArrayList(storage.getBlacklist());

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
        if (storage != null) {
            storage.close();
        }
    }

    @Listener
    public void onPlayerJoin(ClientConnectionEvent.Join event, @Getter("getTargetEntity") Player player) {
        updateUUIDCache(player.getUniqueId().toString(), player.getName());
    }

    private void updateUUIDCache(String uuid, String name) {
        storage.updateUUIDCache(uuid, name);
    }

    private ConfigurationLoader<CommentedConfigurationNode> getConfigManager() {
        return configManager;
    }

    void subscribe() {
        if (storage instanceof MySqlStorageService) {
            getScheduler().createAsyncExecutor(this)
                    .execute(new MySqlListener(this, (MySqlStorageService) storage));
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

    private String serializeItem(ItemStack itemStack) {
        ConfigurationNode node = DataTranslators.CONFIGURATION_NODE.translate(itemStack.toContainer());
        StringWriter stringWriter = new StringWriter();
        try {
            HoconConfigurationLoader.builder().setSink(() -> new BufferedWriter(stringWriter)).build().save(node);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return stringWriter.toString();
    }

    private Optional<ItemStack> deserializeItemStack(String item) {
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
        if (itemStack.getQuantity() < quantityPerSale || quantityPerSale <= 0 || isBlacklisted(itemStack)) return 0;
        if (checkForOtherListings(itemStack, player.getUniqueId().toString())) return -1;
        Listing listing = new Listing(0, serializeItem(itemStack), player.getUniqueId().toString(),
                itemStack.getQuantity(), price, quantityPerSale);
        return storage.createListing(listing);
    }

    private boolean checkForOtherListings(ItemStack itemStack, String s) {
        List<Listing> listings = storage.getListings();
        for (Listing listing : listings) {
            if (!listing.getSeller().equals(s)) continue;
            Optional<ItemStack> ooi = deserializeItemStack(listing.getItem());
            if (!ooi.isPresent()) continue;
            if (matchItemStacks(ooi.get(), itemStack)) {
                return true;
            }
        }
        return false;
    }

    public PaginationList getListings() {
        List<Listing> all = storage.getListings();
        List<Text> texts = new ArrayList<>();
        for (Listing listing : all) {
            Text.Builder l = Text.builder();
            Optional<ItemStack> is = deserializeItemStack(listing.getItem());
            if (!is.isPresent()) continue;
            l.append(Texts.quickItemFormat(is.get()));
            l.append(Text.of(" "));
            l.append(Text.of(TextColors.WHITE, "@"));
            l.append(Text.of(" "));
            l.append(Text.of(TextColors.GREEN, "$" + listing.getPrice()));
            l.append(Text.of(" "));
            l.append(Text.of(TextColors.WHITE, "for"));
            l.append(Text.of(" "));
            l.append(Text.of(TextColors.GREEN, listing.getQuantity() + "x"));
            l.append(Text.of(" "));
            l.append(Text.of(TextColors.WHITE, "Seller:"));
            l.append(Text.of(TextColors.LIGHT_PURPLE, " " + storage.getNameFromUUID(listing.getSeller())));
            l.append(Text.of(" "));
            l.append(Text.builder()
                    .color(TextColors.GREEN)
                    .onClick(TextActions.runCommand("/market check " + listing.getId()))
                    .append(Text.of("[Info]"))
                    .onHover(TextActions.showText(Text.of("View more info about this listing.")))
                    .build());

            texts.add(l.build());
        }
        return getPaginationService().builder().contents(texts).title(Texts.MARKET_LISTINGS).build();
    }

    public List<ItemStack> removeListing(String id, String uuid, boolean staff) {
        int lid = Integer.parseInt(id);
        Optional<Listing> opt = storage.getListing(lid);
        if (!opt.isPresent()) return null;
        Listing listing = opt.get();
        if (!listing.getSeller().equals(uuid) && !staff) return null;
        int inStock = listing.getStock();
        ItemStack listingIS = deserializeItemStack(listing.getItem()).get();
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
        storage.removeListing(lid);
        return stacks;
    }

    public PaginationList getListing(String id) {
        int lid = Integer.parseInt(id);
        Optional<Listing> opt = storage.getListing(lid);
        if (!opt.isPresent()) return null;
        Listing listing = opt.get();
        List<Text> texts = new ArrayList<>();
        texts.add(Texts.quickItemFormat(deserializeItemStack(listing.getItem()).get()));
        texts.add(Text.of("Seller: " + storage.getNameFromUUID(listing.getSeller())));
        texts.add(Text.of("Quantity Per Sale: " + listing.getQuantity() + "x"));
        texts.add(Text.of("Price: $" + listing.getPrice()));
        texts.add(Text.of("Stock: " + listing.getStock()));

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

    public boolean addStock(ItemStack itemStack, String id, UUID uuid) {
        int lid = Integer.parseInt(id);
        Optional<Listing> opt = storage.getListing(lid);
        if (!opt.isPresent()) return false;
        Listing listing = opt.get();
        if (!listing.getSeller().equals(uuid.toString())) return false;
        ItemStack listingStack = deserializeItemStack(listing.getItem()).get();
        if (matchItemStacks(listingStack, itemStack)) {
            listing.setStock(listing.getStock() + itemStack.getQuantity());
            storage.updateListing(listing);
            return true;
        } else return false;
    }

    private boolean matchItemStacks(ItemStack is0, ItemStack is1) {
        return new DataComparator().compare(is0, is1) == 0;
    }

    public ItemStack purchase(UniqueAccount uniqueAccount, String id) {
        int lid = Integer.parseInt(id);
        Optional<Listing> opt = storage.getListing(lid);
        if (!opt.isPresent()) return null;
        Listing listing = opt.get();
        TransactionResult tr = uniqueAccount.transfer(
                getEconomyService().getOrCreateAccount(UUID.fromString(listing.getSeller())).get(),
                getEconomyService().getDefaultCurrency(),
                BigDecimal.valueOf(listing.getPrice()),
                marketCause
        );
        if (tr.getResult().equals(ResultType.SUCCESS)) {
            ItemStack is = deserializeItemStack(listing.getItem()).get();
            int quant = listing.getQuantity();
            int newQuant = listing.getStock() - quant;
            if (newQuant < quant) {
                storage.removeListing(lid);
            } else {
                listing.setStock(newQuant);
                storage.updateListing(listing);
            }
            ItemStack nis = is.copy();
            nis.setQuantity(quant);
            return nis;
        } else {
            return null;
        }
    }

    public EconomyService getEconomyService() {
        return game.getServiceManager().provide(EconomyService.class).get();
    }

    public boolean blacklistAddCmd(String id) {
        if (storage.getBlacklist().contains(id)) return false;
        storage.addToBlacklist(id);
        addIDToBlackList(id);
        return true;
    }

    public boolean blacklistRemoveCmd(String id) {
        if (!storage.getBlacklist().contains(id)) return false;
        storage.removeFromBlacklist(id);
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
        List<Listing> listings = storage.getListings();
        List<Text> texts = new ArrayList<>();
        for (Listing listing : listings) {
            Optional<ItemStack> is = deserializeItemStack(listing.getItem());
            if (!is.isPresent()) continue;
            if (is.get().getItem().equals(itemType)) {
                Text.Builder l = Text.builder();
                l.append(Texts.quickItemFormat(is.get()));
                l.append(Text.of(" "));
                l.append(Text.of(TextColors.WHITE, "@"));
                l.append(Text.of(" "));
                l.append(Text.of(TextColors.GREEN, "$" + listing.getPrice()));
                l.append(Text.of(" "));
                l.append(Text.of(TextColors.WHITE, "for"));
                l.append(Text.of(" "));
                l.append(Text.of(TextColors.GREEN, listing.getQuantity() + "x"));
                l.append(Text.of(" "));
                l.append(Text.of(TextColors.WHITE, "Seller:"));
                l.append(Text.of(TextColors.LIGHT_PURPLE, " " + storage.getNameFromUUID(listing.getSeller())));
                l.append(Text.of(" "));
                l.append(Text.builder()
                        .color(TextColors.GREEN)
                        .onClick(TextActions.runCommand("/market check " + listing.getId()))
                        .append(Text.of("[Info]"))
                        .onHover(TextActions.showText(Text.of("View more info about this listing.")))
                        .build());
                texts.add(l.build());
            }
        }
        if (texts.size() == 0) texts.add(Text.of(TextColors.RED, "No listings found."));
        return getPaginationService().builder().contents(texts).title(Texts.MARKET_SEARCH).build();
    }

    public PaginationList searchForUUID(UUID uniqueId) {
        List<Listing> listings = storage.getListings();
        List<Text> texts = new ArrayList<>();
        for (Listing listing : listings) {
            if (listing.getSeller().equals(uniqueId.toString())) {
                Optional<ItemStack> is = deserializeItemStack(listing.getItem());
                if (!is.isPresent()) continue;
                Text.Builder l = Text.builder();
                l.append(Texts.quickItemFormat(is.get()));
                l.append(Text.of(" "));
                l.append(Text.of(TextColors.WHITE, "@"));
                l.append(Text.of(" "));
                l.append(Text.of(TextColors.GREEN, "$" + listing.getPrice()));
                l.append(Text.of(" "));
                l.append(Text.of(TextColors.WHITE, "for"));
                l.append(Text.of(" "));
                l.append(Text.of(TextColors.GREEN, listing.getQuantity() + "x"));
                l.append(Text.of(" "));
                l.append(Text.of(TextColors.WHITE, "Seller:"));
                l.append(Text.of(TextColors.LIGHT_PURPLE, " " + storage.getNameFromUUID(listing.getSeller())));
                l.append(Text.of(" "));
                l.append(Text.builder()
                        .color(TextColors.GREEN)
                        .onClick(TextActions.runCommand("/market check " + listing.getId()))
                        .append(Text.of("[Info]"))
                        .onHover(TextActions.showText(Text.of("View more info about this listing.")))
                        .build());
                texts.add(l.build());
            }
        }
        if (texts.size() == 0) texts.add(Text.of(TextColors.RED, "No listings found."));
        return getPaginationService().builder().contents(texts).title(Texts.MARKET_SEARCH).build();
    }

    public Scheduler getScheduler() {
        return game.getScheduler();
    }
}
