package xyz.jupp.minecraft.utils;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.cache.TeamBlockCache;
import xyz.jupp.minecraft.cache.TeamBlockCacheObject;

import java.util.HashMap;


public class TeamBlock {

    private final static HashMap<String, Integer> teamMap = new HashMap<>();
    public static HashMap<String, Integer> getRedeemableItems() {
        if (teamMap.isEmpty()) {
            teamMap.put("DRAGON_EGG", 50);
            teamMap.put("BLAZE_ROD", 2);
            teamMap.put("EMERALD_BLOCK", 2);
            teamMap.put("DIAMOND_BLOCK", 10);
            teamMap.put("ELYTRA", 60);
            teamMap.put("NETHER_STAR", 100);
            teamMap.put("TOTEM_OF_UNDYING", 10);
            teamMap.put("CONDUIT", 20);
            teamMap.put("MUSIC_DISC_PIGSTEP", 50);
            teamMap.put("ENCHANTED_GOLDEN_APPLE", 10);
            teamMap.put("WITHER_SKELETON_SKULL", 10);
            teamMap.put("HONEY_BLOCK", 2);
            teamMap.put("PUFFERFISH", 2);
            teamMap.put("TRIDENT", 5);
            teamMap.put("ZOMBIE_HEAD", 22);
            teamMap.put("CREEPER_HEAD", 22);
            teamMap.put("TURTLE_HELMET", 5);
            teamMap.put("SHULKER_SHELL", 8);
        }
        return teamMap;
    }


    private final static HashMap<String, Material> colorRefMap= new HashMap<>();
    public static HashMap<String, Material> getColorRefMap() {
        if (colorRefMap.isEmpty()) {
            colorRefMap.put("§4", Material.RED_WOOL);
            colorRefMap.put("§c", Material.RED_TERRACOTTA);
            colorRefMap.put("§6", Material.ORANGE_WOOL);
            colorRefMap.put("§e", Material.YELLOW_WOOL);
            colorRefMap.put("§2", Material.GREEN_WOOL);
            colorRefMap.put("§b", Material.LIGHT_BLUE_WOOL);
            colorRefMap.put("§3", Material.CYAN_WOOL);
            colorRefMap.put("§1", Material.BLUE_WOOL);
            colorRefMap.put("§9", Material.BLUE_TERRACOTTA);
            colorRefMap.put("§d", Material.PINK_WOOL);
            colorRefMap.put("§5", Material.PURPLE_WOOL);
            colorRefMap.put("§f", Material.WHITE_WOOL);
            colorRefMap.put("§7", Material.LIGHT_GRAY_WOOL);
            colorRefMap.put("§8", Material.GRAY_WOOL);
            colorRefMap.put("§0", Material.BLACK_WOOL);
        }
        return colorRefMap;
    }


    public static boolean isTeamBlock(@NotNull Block block, PlayerCacheObject playerCacheObject) {
        TeamBlockCacheObject teamBlockCacheObject = TeamBlockCache.getTeamBlock(playerCacheObject.getTeamID());
        if (teamBlockCacheObject == null) return false;
        Location teamBlockLoc = teamBlockCacheObject.getLocation();
        return (block.getX() == teamBlockLoc.getX()) && (block.getY() == teamBlockLoc.getY()) && (block.getZ() == teamBlockLoc.getZ());
    }


    public static ItemStack createTeamBlock(@NotNull PlayerCacheObject playerCacheObject) {
        ItemStack itemStack = new ItemStack(getColorRefMap().get(playerCacheObject.getTeamColor()));
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.setDisplayName(playerCacheObject.getTeamColor() + "TeamBlock");
        itemMeta.setUnbreakable(true);
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }


}
