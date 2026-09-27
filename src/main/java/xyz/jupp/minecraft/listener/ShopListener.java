package xyz.jupp.minecraft.listener;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import xyz.jupp.minecraft.economy.BlackMarketView;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.economy.HondoView;
import xyz.jupp.minecraft.economy.NomadView;
import xyz.jupp.minecraft.economy.ShopView;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.utils.Logger;
import xyz.jupp.minecraft.utils.Npcs;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;


public class ShopListener implements Listener {

    @EventHandler
    public void onInteractWithShopVillager(PlayerInteractEntityEvent event) {
        Entity interactedEntity = event.getRightClicked();
        EntityType entityType = interactedEntity.getType();

        if (entityType == EntityType.VILLAGER) {
            String villagerName = Npcs.visibleName(interactedEntity);
            if (Main.getShopVillagerName().equals(villagerName)) {
                event.setCancelled(true);
                ShopView.open(event.getPlayer());
                return;
            }

            if (Main.getFinanceVillagerFredName().equals(villagerName)) {
                event.setCancelled(true);
                depositCash(event.getPlayer());
                return;
            }

            if (Main.getJewelerVillagerName().equals(villagerName)) {
                event.setCancelled(true);
                HondoView.open(event.getPlayer());
                return;
            }
        }

        if (entityType == EntityType.VINDICATOR) {
            if (Main.getBlackMarketDealerVillagerName().equals(Text.legacyOrNull(interactedEntity.customName()))) {
                event.setCancelled(true);
                BlackMarketView.open(event.getPlayer());
            }
            return;
        }

        if (entityType == EntityType.WANDERING_TRADER && Main.getTeamPointsDealerVillagerName().equals(Npcs.visibleName(interactedEntity))) {
            event.setCancelled(true);
            NomadView.open(event.getPlayer());
        }
    }

    // Basil: cash in the main hand goes to the account
    private static void depositCash(Player player) {
        ItemStack itemStack = player.getInventory().getItemInMainHand();

        if (itemStack.getType() == Material.AIR) {
            player.sendMessage(Main.getChatPrefix() + "§fDu hast kein Bargeld in der Hand, das du einzahlen kannst.");
            return;
        }

        // Überprüfen, ob es sich um Smaragde handelt
        if (itemStack.getType() == Material.EMERALD) {
            ItemMeta itemMeta = itemStack.getItemMeta();

            if (itemMeta != null && Main.getCurrencyName(10).equals(Text.legacy(itemMeta.customName()))) {
                int amountToDeposit = itemStack.getAmount() * 10; // Jeder Emerald entspricht 10 Schilling

                // Stack aus der Hand entfernen
                player.getInventory().setItemInMainHand(null);

                Tasks.async(() -> {
                    PlayerRepository.addMoney(player, amountToDeposit);
                    Logger.console("deposit from " + player.getUniqueId() + " (" + amountToDeposit + ")");
                    MainThread.run(() -> {
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f, 2f);
                        player.sendMessage(Main.getChatPrefix() + "§fDu hast " + Main.getCurrencyName(amountToDeposit) + " §ferfolgreich auf dein Konto eingezahlt.");
                    });
                });
                return;
            }
        }

        // Wenn keine gültigen Smaragde in der Hand sind
        player.sendMessage(Main.getChatPrefix() + "§fDu kannst nur gültiges §5Bargeld §feinzahlen.");
    }


    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (Npcs.isNpc(event.getEntity())) event.setCancelled(true);
    }


    // NPCs created before they were made persistent get it when their chunk loads
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof LivingEntity living && Npcs.isNpc(living)) Npcs.keep(living);
        }
    }

}
