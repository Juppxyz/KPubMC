package xyz.jupp.minecraft.utils;

import java.util.HashSet;
import java.util.Set;

public class ClaimedAreaHelper {

    private static final Set<String> playersInClaimedAreas = new HashSet<>();

    public static Set<String> getPlayersInClaimedAreas() {
        return playersInClaimedAreas;
    }

    public static void addPlayerToClaimedAreas(String player) {
        playersInClaimedAreas.add(player);
    }

    public static void removePlayerFromClaimedAreas(String player) {
        playersInClaimedAreas.remove(player);
    }



    private static final Set<String> teamPlayerInArea = new HashSet<>();
    public static Set<String> getTeamPlayerInArea() {
        return playersInClaimedAreas;
    }

    public static void addTeamPlayerInArea(String player) {
        playersInClaimedAreas.add(player);
    }

    public static void removeTeamPlayerInArea(String player) {
        playersInClaimedAreas.remove(player);
    }



}
