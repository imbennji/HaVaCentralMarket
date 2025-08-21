package com.kookykraftmc.market;

import org.junit.Test;
import org.spongepowered.api.item.inventory.ItemStack;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class DataComparatorTest {

    private final DataComparator comparator = new DataComparator();

    @Test
    public void testBothNull() {
        assertTrue(comparator.test(null, null));
    }

    @Test
    public void testOneNull() {
        ItemStack stack = mock(ItemStack.class);
        assertFalse(comparator.test(stack, null));
        assertFalse(comparator.test(null, stack));
    }

    @Test
    public void testReflexive() {
        ItemStack stack = mock(ItemStack.class);
        when(stack.copy()).thenReturn(stack);
        when(stack.equalTo(stack)).thenReturn(true);
        assertTrue(comparator.test(stack, stack));
    }

    @Test
    public void testSymmetricEquality() {
        ItemStack a = mock(ItemStack.class);
        ItemStack b = mock(ItemStack.class);
        when(a.copy()).thenReturn(a);
        when(b.copy()).thenReturn(b);
        when(a.equalTo(b)).thenReturn(true);
        when(b.equalTo(a)).thenReturn(true);
        assertTrue(comparator.test(a, b));
        assertTrue(comparator.test(b, a));
    }

    @Test
    public void testSymmetricInequality() {
        ItemStack a = mock(ItemStack.class);
        ItemStack b = mock(ItemStack.class);
        when(a.copy()).thenReturn(a);
        when(b.copy()).thenReturn(b);
        when(a.equalTo(b)).thenReturn(false);
        when(b.equalTo(a)).thenReturn(false);
        assertFalse(comparator.test(a, b));
        assertFalse(comparator.test(b, a));
    }
}
