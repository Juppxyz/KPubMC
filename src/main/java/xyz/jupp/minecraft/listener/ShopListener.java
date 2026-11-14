package xyz.jupp.minecraft.listener;

import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.building.BauamtView;
import xyz.jupp.minecraft.inventory.Loading;
import xyz.jupp.minecraft.economy.BankView;
import xyz.jupp.minecraft.economy.BlackMarketView;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.economy.HondoView;
import xyz.jupp.minecraft.economy.NomadView;
import xyz.jupp.minecraft.economy.ShopView;
import xyz.jupp.minecraft.jail.SheriffView;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.Npcs;
import xyz.jupp.minecraft.utils.Text;

import java.util.function.Consumer;

public class ShopListener implements Listener {

    @EventHandler
    public void onInteractWithShopVillager(PlayerInteractEntityEvent event) {
        Entity interactedEntity = event.getRightClicked();
        EntityType entityType = interactedEntity.getType();
        // this handler runs for cancelled clicks too (spawn protection cancels them), so the prison lock is checked here
        if (JailHandler.isCaughtPrisoner(event.getPlayer())) return;

        Consumer<Player> open = npcMenu(interactedEntity, entityType);
        if (open == null) return;
        // always cancelled (no vanilla trade window, also for the off-hand click); the menu itself at most once per short while
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (Loading.allow(player.getUniqueId())) open.accept(player);
    }

    // the menu behind this NPC, null for every other entity
    private static @Nullable Consumer<Player> npcMenu(Entity entity, EntityType entityType) {
        if (entityType == EntityType.VILLAGER) {
            String villagerName = Npcs.visibleName(entity);
            if (Main.getShopVillagerName().equals(villagerName)) return ShopView::open;
            if (Main.getFinanceVillagerFredName().equals(villagerName)) return BankView::open;
            if (Main.getJewelerVillagerName().equals(villagerName)) return HondoView::open;
            if (Main.getBauamtVillagerName().equals(villagerName)) return BauamtView::open;
            if (Main.getSheriffVillagerName().equals(villagerName)) return SheriffView::open;
            return null;
        }
        if (entityType == EntityType.VINDICATOR) {
            return Main.getBlackMarketDealerVillagerName().equals(Text.legacyOrNull(entity.customName())) ? BlackMarketView::open : null;
        }
        if (entityType == EntityType.WANDERING_TRADER && Main.getTeamPointsDealerVillagerName().equals(Npcs.visibleName(entity))) {
            return NomadView::open;
        }
        return null;
    }

    // /kill stays possible, that is how an operator removes an NPC again
    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.KILL && Npcs.isNpc(event.getEntity())) event.setCancelled(true);
    }


    // NPCs created before they were made persistent get it when their chunk loads
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof LivingEntity living && Npcs.isNpc(living)) Npcs.keep(living);
        }
    }

}
