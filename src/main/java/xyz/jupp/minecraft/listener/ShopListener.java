package xyz.jupp.minecraft.listener;

import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import xyz.jupp.minecraft.economy.BankView;
import xyz.jupp.minecraft.economy.BlackMarketView;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.economy.HondoView;
import xyz.jupp.minecraft.economy.NomadView;
import xyz.jupp.minecraft.economy.ShopView;
import xyz.jupp.minecraft.utils.Npcs;
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
                BankView.open(event.getPlayer());
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
