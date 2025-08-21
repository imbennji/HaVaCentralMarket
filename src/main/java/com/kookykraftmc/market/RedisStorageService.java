package com.kookykraftmc.market;

import com.google.common.collect.Lists;
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
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Transaction;

import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.hocon.HoconConfigurationLoader;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import org.spongepowered.api.data.DataView;
import org.spongepowered.api.data.persistence.DataTranslators;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Redis based storage implementation.
 */
public class RedisStorageService implements StorageService {

    private final JedisPool jedisPool;
    private final Game game;

    public RedisStorageService(String host, int port, boolean usePassword, String password, Game game) {
        JedisPoolConfig config = new JedisPoolConfig();
        if (usePassword) {
            this.jedisPool = new JedisPool(config, host, port, 2000, password);
        } else {
            this.jedisPool = new JedisPool(config, host, port);
        }
        this.game = game;
    }

    private Jedis getJedis() {
        return jedisPool.getResource();
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
        try (Jedis jedis = getJedis()) {
            jedis.hset(RedisKeys.UUID_CACHE, uuid, name);
        }
    }

    @Override
    public String getCachedName(String uuid) {
        try (Jedis jedis = getJedis()) {
            return jedis.hget(RedisKeys.UUID_CACHE, uuid);
        }
    }

    @Override
    public List<String> getBlacklistedItems() {
        try (Jedis jedis = getJedis()) {
            return Lists.newArrayList(jedis.hgetAll(RedisKeys.BLACKLIST).keySet());
        }
    }

    @Override
    public boolean addBlacklistedItem(String id) {
        try (Jedis jedis = getJedis()) {
            if (jedis.hexists(RedisKeys.BLACKLIST, id)) return false;
            jedis.hset(RedisKeys.BLACKLIST, id, String.valueOf(true));
        }
        return true;
    }

    @Override
    public boolean removeBlacklistedItem(String id) {
        try (Jedis jedis = getJedis()) {
            if (!jedis.hexists(RedisKeys.BLACKLIST, id)) return false;
            jedis.hdel(RedisKeys.BLACKLIST, id);
        }
        return true;
    }

    @Override
    public int addListing(Player player, ItemStack itemStack, int quantityPerSale, int price) {
        try (Jedis jedis = getJedis()) {
            if (itemStack.getQuantity() < quantityPerSale || quantityPerSale <= 0 || isBlacklisted(itemStack)) return 0;
            if (!jedis.exists(RedisKeys.LAST_MARKET_ID)) {
                jedis.set(RedisKeys.LAST_MARKET_ID, String.valueOf(1));
                int id = 1;
                String key = RedisKeys.MARKET_ITEM_KEY(String.valueOf(id));
                Transaction m = jedis.multi();
                m.hset(key, "Item", serializeItem(itemStack));
                m.hset(key, "Seller", player.getUniqueId().toString());
                m.hset(key, "Stock", String.valueOf(itemStack.getQuantity()));
                m.hset(key, "Price", String.valueOf(price));
                m.hset(key, "Quantity", String.valueOf(quantityPerSale));
                m.exec();
                jedis.hset(RedisKeys.FOR_SALE, String.valueOf(id), player.getUniqueId().toString());
                jedis.incr(RedisKeys.LAST_MARKET_ID);
                return id;
            } else {
                int id = Integer.parseInt(jedis.get(RedisKeys.LAST_MARKET_ID));
                String key = RedisKeys.MARKET_ITEM_KEY(String.valueOf(id));
                if (checkForOtherListings(itemStack, player.getUniqueId().toString())) return -1;
                Transaction m = jedis.multi();
                m.hset(key, "Item", serializeItem(itemStack));
                m.hset(key, "Seller", player.getUniqueId().toString());
                m.hset(key, "Stock", String.valueOf(itemStack.getQuantity()));
                m.hset(key, "Price", String.valueOf(price));
                m.hset(key, "Quantity", String.valueOf(quantityPerSale));
                m.exec();
                jedis.hset(RedisKeys.FOR_SALE, String.valueOf(id), player.getUniqueId().toString());
                jedis.incr(RedisKeys.LAST_MARKET_ID);
                return id;
            }
        }
    }

    private boolean checkForOtherListings(ItemStack itemStack, String s) {
        try (Jedis jedis = getJedis()) {
            Map<String, String> d = jedis.hgetAll(RedisKeys.FOR_SALE);
            Map<String, String> e = d.entrySet().stream()
                    .filter(stringStringEntry -> stringStringEntry.getValue().equals(s))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            if (e.size() == 0) return false;
            else {
                final boolean[] hasOther = {false};
                e.forEach((s1, s2) -> {
                    Optional<ItemStack> ooi = deserializeItemStack(jedis.hget(RedisKeys.MARKET_ITEM_KEY(s1), "Item"));
                    if (!ooi.isPresent()) return;
                    if (matchItemStacks(ooi.get(), itemStack)) {
                        hasOther[0] = true;
                    }
                });
                return hasOther[0];
            }
        }
    }

    private boolean isBlacklisted(ItemStack itemStack) {
        Optional<BlockType> type = itemStack.getItem().getBlock();
        String id = type.map(blockType -> blockType.getDefaultState().getId())
                .orElseGet(() -> itemStack.getItem().getId());
        return getBlacklistedItems().contains(id);
    }

    @Override
    public List<ItemStack> removeListing(String id, String uuid, boolean staff) {
        try (Jedis jedis = getJedis()) {
            if (!jedis.hexists(RedisKeys.FOR_SALE, id)) return null;
            else {
                Map<String, String> listing = jedis.hgetAll(RedisKeys.MARKET_ITEM_KEY(id));
                if (!listing.get("Seller").equals(uuid) && !staff) return null;
                int inStock = Integer.parseInt(listing.get("Stock"));
                ItemStack listingIS = deserializeItemStack(listing.get("Item")).get();
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
                jedis.hdel(RedisKeys.FOR_SALE, id);
                return stacks;
            }
        }
    }

    @Override
    public PaginationList getListings() {
        try (Jedis jedis = getJedis()) {
            Set<String> openListings = jedis.hgetAll(RedisKeys.FOR_SALE).keySet();
            List<Text> texts = new ArrayList<>();
            for (String openListing : openListings) {
                Map<String, String> listing = jedis.hgetAll(RedisKeys.MARKET_ITEM_KEY(openListing));
                Text.Builder l = Text.builder();
                Optional<ItemStack> is = deserializeItemStack(listing.get("Item"));
                l.append(Texts.quickItemFormat(is.get()));
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
            return Market.instance.getPaginationService().builder().contents(texts).title(Texts.MARKET_LISTINGS).build();
        }
    }

    @Override
    public PaginationList getListing(String id) {
        try (Jedis jedis = getJedis()) {
            if (!jedis.hexists(RedisKeys.FOR_SALE, id)) return null;
            Map<String, String> listing = jedis.hgetAll(RedisKeys.MARKET_ITEM_KEY(id));
            List<Text> texts = new ArrayList<>();
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
            return Market.instance.getPaginationService().builder().title(Texts.MARKET_LISTING(id)).contents(texts).build();
        }
    }

    @Override
    public boolean addStock(ItemStack itemStack, String id, UUID uuid) {
        try (Jedis jedis = getJedis()) {
            if (!jedis.hexists(RedisKeys.FOR_SALE, id)) return false;
            else if (!jedis.hget(RedisKeys.MARKET_ITEM_KEY(id), "Seller").equals(uuid.toString())) return false;
            else {
                ItemStack listingStack = deserializeItemStack(jedis.hget(RedisKeys.MARKET_ITEM_KEY(id), "Item")).get();
                if (matchItemStacks(listingStack, itemStack)) {
                    int stock = Integer.parseInt(jedis.hget(RedisKeys.MARKET_ITEM_KEY(id), "Stock"));
                    int quan = itemStack.getQuantity() + stock;
                    jedis.hset(RedisKeys.MARKET_ITEM_KEY(id), "Stock", String.valueOf(quan));
                    return true;
                } else return false;
            }
        }
    }

    @Override
    public ItemStack purchase(UniqueAccount uniqueAccount, String id) {
        try (Jedis jedis = getJedis()) {
            if (!jedis.hexists(RedisKeys.FOR_SALE, id)) return null;
            else {
                TransactionResult tr = uniqueAccount.transfer(
                        Market.instance.getEconomyService().getOrCreateAccount(UUID.fromString(jedis.hget(RedisKeys.MARKET_ITEM_KEY(id), "Seller"))).get(),
                        Market.instance.getEconomyService().getDefaultCurrency(),
                        BigDecimal.valueOf(Long.parseLong(jedis.hget(RedisKeys.MARKET_ITEM_KEY(id), "Price"))),
                        Market.instance.getMarketCause()
                );
                if (tr.getResult().equals(ResultType.SUCCESS)) {
                    ItemStack is = deserializeItemStack(jedis.hget(RedisKeys.MARKET_ITEM_KEY(id), "Item")).get();
                    int quant = Integer.parseInt(jedis.hget(RedisKeys.MARKET_ITEM_KEY(id), "Quantity"));
                    int inStock = Integer.parseInt(jedis.hget(RedisKeys.MARKET_ITEM_KEY(id), "Stock"));
                    int newQuant = inStock - quant;
                    if (newQuant < quant) {
                        jedis.hdel(RedisKeys.FOR_SALE, id);
                    } else {
                        jedis.hset(RedisKeys.MARKET_ITEM_KEY(id), "Stock", String.valueOf(newQuant));
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

    @Override
    public PaginationList searchForItem(ItemType itemType) {
        try (Jedis jedis = getJedis()) {
            Set<String> openListings = jedis.hgetAll(RedisKeys.FOR_SALE).keySet();
            List<Text> texts = new ArrayList<>();
            for (String openListing : openListings) {
                Map<String, String> listing = jedis.hgetAll(RedisKeys.MARKET_ITEM_KEY(openListing));
                Optional<ItemStack> ooi = deserializeItemStack(listing.get("Item"));
                if (!ooi.isPresent()) continue;
                if (ooi.get().getItem().equals(itemType)) {
                    Text.Builder l = Text.builder();
                    ItemStack is = ooi.get();
                    l.append(Texts.quickItemFormat(is));
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
            return Market.instance.getPaginationService().builder().contents(texts).title(Texts.MARKET_SEARCH).build();
        }
    }

    @Override
    public PaginationList searchForUUID(UUID uniqueId) {
        try (Jedis jedis = getJedis()) {
            Set<String> openListings = jedis.hgetAll(RedisKeys.FOR_SALE).keySet();
            List<Text> texts = new ArrayList<>();
            for (String openListing : openListings) {
                Map<String, String> listing = jedis.hgetAll(RedisKeys.MARKET_ITEM_KEY(openListing));
                if (listing.get("Seller").equals(uniqueId.toString())) {
                    Text.Builder l = Text.builder();
                    Optional<ItemStack> is = deserializeItemStack(listing.get("Item"));
                    if (!is.isPresent()) continue;
                    ItemStack item = is.get();
                    l.append(Texts.quickItemFormat(item));
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
            return Market.instance.getPaginationService().builder().contents(texts).title(Texts.MARKET_SEARCH).build();
        }
    }
}
