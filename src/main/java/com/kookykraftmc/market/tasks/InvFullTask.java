package com.kookykraftmc.market.tasks;

import com.kookykraftmc.market.Market;
import com.kookykraftmc.market.Texts;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.item.inventory.ItemStack;
import org.spongepowered.api.item.inventory.entity.Hotbar;
import org.spongepowered.api.item.inventory.transaction.InventoryTransactionResult;
import org.spongepowered.api.item.inventory.type.GridInventory;

import java.util.concurrent.TimeUnit;

/**
 * Created by TimeTheCat on 4/3/2017.
 */
public class InvFullTask implements Runnable {
    private static final int MAX_ATTEMPTS = 5;
    private Market pl = Market.instance;
    private Player player;
    private ItemStack item;
    private int attemptsLeft;

    public InvFullTask(ItemStack item, Player player) {
        this(item, player, MAX_ATTEMPTS);
    }

    private InvFullTask(ItemStack item, Player player, int attemptsLeft) {
        this.item = item;
        this.player = player;
        this.attemptsLeft = attemptsLeft;
    }

    @Override
    public void run() {
        InventoryTransactionResult offer = player.getInventory().query(Hotbar.class, GridInventory.class).offer(item);
        if (!offer.getType().equals(InventoryTransactionResult.Type.SUCCESS)) {
            if (attemptsLeft > 1) {
                player.sendMessage(Texts.INV_FULL);
                pl.getScheduler().createTaskBuilder()
                        .name("Market Delivery")
                        .execute(new InvFullTask(item, player, attemptsLeft - 1))
                        .delay(30, TimeUnit.SECONDS)
                        .submit(pl);
            } else {
                player.sendMessage(Texts.DELIVERY_FAILED);
            }
        } else {
            player.sendMessage(Texts.PURCHASE_SUCCESSFUL);
        }
    }
}
