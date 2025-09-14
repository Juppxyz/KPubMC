package xyz.jupp.minecraft.listener;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.*;
import xyz.jupp.minecraft.database.TeamCollection;
import xyz.jupp.minecraft.utils.TeamBlock;

import java.util.ArrayList;
import java.util.Map;
import java.util.Random;

public class TeamBlockListener implements Listener {

    // NOT IN USE

    @EventHandler
    public void onInteractWithTeamBlock(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        @Nullable Block interactItem = event.getClickedBlock();
        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        if (interactItem == null) return;
        if (!event.getAction().equals(Action.RIGHT_CLICK_BLOCK)) return;
        if (player.getInventory().getItemInMainHand().getType().equals(Material.AIR)) return;

        PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(player);
        if (playerCacheObject.getTeamID() == null) return;
        if (TeamBlock.isTeamBlock(interactItem, playerCacheObject)) {
            event.setCancelled(true);
            if (player.getInventory().getItemInMainHand().getAmount() > 1) {
                player.sendMessage(Main.getChatPrefix() + "Bitte nehme immer nur §cein §fItem in die Hand.");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
                return;
            }

            if (!TeamBlock.getRedeemableItems().containsKey(itemInHand.getType().name())) {
                player.playSound(player.getLocation(), Sound.BLOCK_LAVA_POP, 2f,2f);
                player.sendMessage(playerCacheObject.getTeamColor() +"Team-Info§8» §cDas ist leider keines der gesuchten Gegenstände.");
                player.damage(1.5d);
                return;
            }

            player.setItemInHand(new ItemStack(Material.AIR, 1));
            Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                String teamID = playerCacheObject.getTeamID();
                TeamCollection teamCollection = new TeamCollection(teamID);

                int earnedTeamPoints = TeamBlock.getRedeemableItems().get(itemInHand.getType().name());
                int teamPoints = teamCollection.getTeamPoints();

                teamCollection.changeTeamPoints(teamPoints + earnedTeamPoints);
                Bukkit.getConsoleSender().sendMessage(Main.getChatPrefix() + "§a" + player.getUniqueId() + " §fearned §6" + earnedTeamPoints + " §fTeamPoints. ("+ teamID +")");

                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 2f,2f);
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2f,2f);

                PlayerCacheObject tmpPlayerCacheObject = null;
                String randomEarningText = generateRandomSentence(player.getName(), earnedTeamPoints);
                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    tmpPlayerCacheObject = CacheHandler.getInstance().getPlayerInCache(onlinePlayer);
                    if (tmpPlayerCacheObject.getTeamID() != null
                            && tmpPlayerCacheObject.getTeamID().equals(playerCacheObject.getTeamID())) {
                        onlinePlayer.playSound(onlinePlayer.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST_FAR, 2f,2f);
                        onlinePlayer.sendMessage(playerCacheObject.getTeamColor() + "Team Info §8»" + randomEarningText );
                    }
                }

            });
        }

    }


    @EventHandler
    public void onTeamBlockPlace(BlockPlaceEvent event) {
        ItemStack itemInHand = event.getItemInHand();
        ItemMeta itemMeta = itemInHand.getItemMeta();
        if (itemMeta == null) return;
        PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(event.getPlayer());
        if (playerCacheObject.getTeamID() == null) return;

        if (itemMeta.getDisplayName().equals(playerCacheObject.getTeamColor() + "TeamBlock")) {
            if (!event.getPlayer().getWorld().getEnvironment().equals(World.Environment.NORMAL)) {
                event.getPlayer().sendMessage(Main.getChatPrefix() + "§cDu kannst euren TeamBlock nur in der 'normalen' Welt aufstellen.");
                event.getPlayer().playSound(event.getPlayer().getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS,2f,2f);
                event.setCancelled(true);
            }

            Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                TeamBlockCacheObject teamBlockCacheObject = TeamBlockCache.getTeamBlock(playerCacheObject.getTeamID());
                Block block = event.getBlock();
                teamBlockCacheObject.placeTeamBlock(event.getBlock().getX(),block.getY(), block.getZ());
                Bukkit.getWorld(block.getWorld().getName()).playEffect(block.getLocation(), Effect.MOBSPAWNER_FLAMES, 3);
                Bukkit.getWorld(block.getWorld().getName()).playEffect(block.getLocation(), Effect.SMOKE, 2);
            });
        }
    }


    @EventHandler
    public void onTeamBlockBreakEvent(BlockBreakEvent event) {
        @NotNull Block block = event.getBlock();
        if (!teamBlockMaterials.contains(block.getType().name())) return;

        PlayerCacheObject playerCacheObject = CacheHandler.getInstance().getPlayerInCache(event.getPlayer());
        if (playerCacheObject.getTeamID() == null) {
            checkIfIsATeamBlock(block, playerCacheObject.getPlayer());
            return;
        }

        TeamBlockCacheObject teamBlock = TeamBlockCache.getTeamBlock(playerCacheObject.getTeamID());
        if (teamBlock == null) return;
        Location teamBlockLoc = teamBlock.getLocation();
        if (teamBlockLoc == null) return;

        boolean isTeamBlockLocation = (block.getX() == teamBlockLoc.getX()) && (block.getY() == teamBlockLoc.getY()) && (block.getZ() == teamBlockLoc.getZ());
        if (isTeamBlockLocation) {
            event.getPlayer().playSound(event.getPlayer().getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2f,2f);
            event.getPlayer().sendMessage(Main.getChatPrefix() + "§cDu kannst deinen TeamBlock nicht abbauen.");
            event.getPlayer().damage(1.0d);
            event.setCancelled(true);
            return;
        }

        checkIfIsATeamBlock(block, playerCacheObject.getPlayer());
    }


    private void checkIfIsATeamBlock(@NotNull Block block, @NotNull Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            for (Map.Entry<String, TeamBlockCacheObject> cacheObjectEntry : TeamBlockCache.getTeamBlockCache().entrySet()) {
                Location locationTeamBlock =  cacheObjectEntry.getValue().getLocation();
                boolean isAnyTeamBlockLocation = (block.getX() == locationTeamBlock.getX()) && (block.getY() == locationTeamBlock.getY())
                        && (block.getZ() == locationTeamBlock.getZ());
                if (isAnyTeamBlockLocation) {
                    TeamCacheObject teamCacheObject = CacheHandler.getInstance().getTeamCacheObject(cacheObjectEntry.getKey());
                    player.playSound(player, Sound.BLOCK_ANVIL_BREAK, 2f,2f);
                    player.playSound(player.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 2f,2f);
                    player.sendMessage(Main.getChatPrefix() + "§fDu hast den TeamBlock von " + teamCacheObject.getTeamColor() + teamCacheObject.getTeamName() + " §fzerstört!");

                    cacheObjectEntry.getValue().setActive(false);
                    //teamCacheObject.incTeamBlockAlreadyPurchased();

                    PlayerCacheObject tmpPlayerCacheObject = null;
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        tmpPlayerCacheObject = CacheHandler.getInstance().getPlayerInCache(online);
                        if (tmpPlayerCacheObject.getTeamID() != null && (teamCacheObject.getTeamID().equals(tmpPlayerCacheObject.getTeamID()))) {
                            online.sendMessage(teamCacheObject.getTeamColor() + "Team-Info§8» §fEuer TeamBlock wurde zerstört!!");
                            online.sendMessage(teamCacheObject.getTeamColor() + "Team-Info§8» §fDie TeamPunkte wurden auf §c15 §freduziert!");
                            online.sendMessage(teamCacheObject.getTeamColor() + "Team-Info§8» §fAlle Perks werden deaktiviert, solange kein neuer TeamBlock da ist.");
                            online.playSound(online.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 2f,2f);
                            online.playSound(online.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 2f,2f);
                        }
                    }
                    break;
                }
            }
        });
    }


    private final static ArrayList<String> teamBlockMaterials = new ArrayList<String>() {{
        add(Material.RED_WOOL.name());
        add(Material.RED_TERRACOTTA.name());
        add(Material.ORANGE_WOOL.name());
        add(Material.YELLOW_WOOL.name());
        add(Material.GREEN_WOOL.name());
        add(Material.LIGHT_BLUE_WOOL.name());
        add(Material.CYAN_WOOL.name());
        add(Material.BLUE_WOOL.name());
        add(Material.BLUE_TERRACOTTA.name());
        add(Material.PINK_WOOL.name());
        add(Material.PURPLE_WOOL.name());
        add(Material.WHITE_WOOL.name());
        add(Material.LIGHT_GRAY_WOOL.name());
        add(Material.GRAY_WOOL.name());
        add(Material.BLACK_WOOL.name());
    }};



    @EventHandler
    public void onBlockExplosion(BlockExplodeEvent event) {
        Block block = event.getBlock();
        if (teamBlockMaterials.contains(block.getType().name())) {
            Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
                for (Map.Entry<String, TeamBlockCacheObject> cacheObjectEntry : TeamBlockCache.getTeamBlockCache().entrySet()) {
                    Location locationTeamBlock = cacheObjectEntry.getValue().getLocation();
                    boolean isAnyTeamBlockLocation = (block.getX() == locationTeamBlock.getX()) && (block.getY() == locationTeamBlock.getY()) && (block.getZ() == locationTeamBlock.getZ());
                    if (isAnyTeamBlockLocation) {
                        event.setCancelled(true);
                        break;
                    }
                }
            });
        }
    }


    private static final Random randomInt = new Random();
    private static final String[] randomEarnedTexts = {
            "§fEin beeindruckendes Ergebnis von §a%s§f: §a%i§f TeamPunkte.",
            "§fEin herzlicher Applaus für §a%s§f und seine zusätzlichen §a%i§f TeamPunkte.",
            "§fMit großer Hingabe sammelte §a%s§f weitere §a%i§f TeamPunkte.",
            "§fHeute war ein produktiver Tag für §a%s§f: §a%i§f TeamPunkte dazu.",
            "§fGlückwunsch an §a%s§f für die frisch gesammelten §a%i§f TeamPunkte.",
            "§fDank anhaltender Anstrengung hat §a%s§f §a%i§f TeamPunkte mehr.",
            "§fEs ist ein guter Tag für §a%s§f mit zusätzlichen §a%i§f TeamPunkten.",
            "§fToller Job, §a%s§f! Du hast §a%i§f TeamPunkte bekommen.",
            "§fIn einem glänzenden Moment erzielte §a%s§f §a%i§f TeamPunkte.",
            "§fDie neuesten Ergebnisse zeigen: §a%s§f hat §a%i§f TeamPunkte gesammelt.",
            "§fUnd wieder ein Erfolg für §a%s§f mit §a%i§f TeamPunkten.",
            "§fEin beispielhafter Einsatz von §a%s§f brachte ihm §a%i§f TeamPunkte.",
            "§fEs ist offiziell: §a%s§f hat weitere §a%i§f TeamPunkte erhalten.",
            "§fIn einem spannenden Moment sammelte §a%s§f §a%i§f TeamPunkte.",
            "§fGratulation an §a%s§f für seine neu hinzugefügten §a%i§f TeamPunkte.",
            "§fDie TeamPunktezahlen sind da: §a%s§f hat §a%i§f TeamPunkte bekommen.",
            "§fOhne zu zögern, hat §a%s§f weitere §a%i§f TeamPunkte erzielt.",
            "§fEs ist ein Grund zum Feiern für §a%s§f mit zusätzlichen §a%i§f TeamPunkten.",
            "§fMit einem bemerkenswerten Spielzug sicherte sich §a%s§f §a%i§f TeamPunkte.",
            "§fDie Nachrichten verbreiten sich schnell: §a%s§f hat §a%i§f TeamPunkte gesammelt.",
            "§fIn einem eindrucksvollen Zug erzielte §a%s§f §a%i§f TeamPunkte.",
            "§fDie Fans jubeln, denn §a%s§f hat weitere §a%i§f TeamPunkte bekommen.",
            "§fUnter den Augen aller sammelte §a%s§f §a%i§f TeamPunkte.",
            "§fEin weiterer stolzer Moment für §a%s§f mit §a%i§f zusätzlichen TeamPunkten.",
            "§fDas Spiel hat gezeigt: §a%s§f ist um §a%i§f TeamPunkte reicher."
    };

    public static String generateRandomSentence(String playerName, int points) {
        String template = randomEarnedTexts[randomInt.nextInt(randomEarnedTexts.length)];
        return template.replace("%s", playerName).replace("%i", String.valueOf(points));
    }

    public static ArrayList<String> getTeamBlockMaterials() {
        return teamBlockMaterials;
    }
}