package com.kookykraftmc.market;

import org.spongepowered.api.item.inventory.ItemStack;

import java.util.function.BiPredicate;

/**
 * Created by TimeTheCat on 7/18/2017.
 */
public class DataComparator implements BiPredicate<ItemStack, ItemStack> {

    @Override
    public boolean test(ItemStack o1, ItemStack o2) {
        if (o1 == null && o2 == null) {
            return true;
        }
        if (o1 == null || o2 == null) {
            return false;
        }
        ItemStack c1 = o1.copy(), c2 = o2.copy();
        c1.setQuantity(1);
        c2.setQuantity(1);
        return c1.equalTo(c2);
    }
}