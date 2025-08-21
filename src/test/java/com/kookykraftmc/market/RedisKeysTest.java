package com.kookykraftmc.market;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RedisKeysTest {

    @Test
    public void testLastMarketId() {
        assertEquals("market:alpha:lastID", RedisKeys.lastMarketId("alpha"));
    }

    @Test
    public void testMarketItemKey() {
        assertEquals("market:beta:123", RedisKeys.marketItemKey("beta", "123"));
    }

    @Test
    public void testForSale() {
        assertEquals("market:gamma:open", RedisKeys.forSale("gamma"));
    }
}
