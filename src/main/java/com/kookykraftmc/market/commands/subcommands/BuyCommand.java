package com.kookykraftmc.market.commands.subcommands;

import com.kookykraftmc.market.Market;
import com.kookykraftmc.market.Texts;
import com.kookykraftmc.market.tasks.InvFullTask;
import org.spongepowered.api.command.CommandException;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.command.args.CommandContext;
import org.spongepowered.api.command.spec.CommandExecutor;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.item.inventory.ItemStack;
import org.spongepowered.api.item.inventory.entity.Hotbar;
import org.spongepowered.api.item.inventory.transaction.InventoryTransactionResult;
import org.spongepowered.api.item.inventory.type.GridInventory;
import org.spongepowered.api.service.economy.account.UniqueAccount;
import org.spongepowered.api.text.Text;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Created by TimeTheCat on 3/18/2017.
 */
public class BuyCommand implements CommandExecutor {
    Market pl = Market.instance;
    @Override
    public CommandResult execute(CommandSource src, CommandContext args) throws CommandException {
        Optional<String> id = args.getOne(Text.of("id"));
        if (id.isPresent()) {
            Player player = (Player) src;
            Optional<UniqueAccount> acc = pl.getEconomyService().getOrCreateAccount(player.getUniqueId());
            if (acc.isPresent()) {
                ItemStack a = pl.purchase(acc.get(), id.get());
                if (a == null) player.sendMessage(Texts.NO_BUY_ITEM);
                else {
                    InventoryTransactionResult offer = player.getInventory().query(Hotbar.class, GridInventory.class).offer(a);
                    if (!offer.getType().equals(InventoryTransactionResult.Type.SUCCESS)) {
                        player.sendMessage(Texts.INV_FULL);
                        pl.getScheduler().createTaskBuilder()
                                .name("Market " + player.getName() + " " + id)
                                .execute(new InvFullTask(a, player))
                                .delay(30, TimeUnit.SECONDS)
                                .submit(pl);
                        return CommandResult.success();
                    } else {
                        player.sendMessage(Texts.PURCHASE_SUCCESSFUL);
                        return CommandResult.success();
                    }
                }
            }
        }
        return CommandResult.success();
    }
}
