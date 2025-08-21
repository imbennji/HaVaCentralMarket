package com.kookykraftmc.market;

import org.junit.Test;
import org.spongepowered.api.item.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.Assert.assertFalse;

public class MarketTest {

    @Test
    public void deserializeItemStackWithInvalidStringReturnsEmpty() throws Exception {
        Market market = new Market();
        Method method = Market.class.getDeclaredMethod("deserializeItemStack", String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Optional<ItemStack> result = (Optional<ItemStack>) method.invoke(market, "invalid");
        assertFalse(result.isPresent());
    }
}
