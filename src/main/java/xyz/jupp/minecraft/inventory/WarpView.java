package xyz.jupp.minecraft.inventory;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.cache.WarpCache;
import xyz.jupp.minecraft.cache.WarpCacheObject;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.team.Relations;
import xyz.jupp.minecraft.team.TeamCreateView;
import xyz.jupp.minecraft.team.TeamWarps;
import xyz.jupp.minecraft.team.Teams;
import xyz.jupp.minecraft.utils.PlayerTeleport;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The warp menu (/warp): every player's warp (28 per page), the own warp's buttons and, for team members, the team
 * warp at the top. Clicks go by slot. Only used on the main thread; money and warps change on a worker.
 */
public final class WarpView implements InventoryHolder {

    public static final int CREATE_COST = 5_000;
    public static final int MOVE_COST = 500;
    public static final int TELEPORT_COST = 200;

    private static final int SIZE = 54;
    private static final int[] WARP_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    private static final int SLOT_TEAM_REMOVE = 3;
    private static final int SLOT_TEAM = 4;
    private static final int SLOT_TEAM_SET = 5;
    private static final int SLOT_CLOSE = 8;
    private static final int SLOT_OWN_REMOVE = 45;
    private static final int SLOT_PREVIOUS = 48;
    private static final int SLOT_PAGE = 49;
    private static final int SLOT_NEXT = 50;
    private static final int SLOT_OWN_SET = 53;
    private static final long CLICK_COOLDOWN_MILLIS = 300;
    private static final long CONFIRM_MILLIS = 5_000;

    // a warp owner with the name from the player file (read on a worker)
    private record Entry(UUID owner, String name) {}

    // paid teleports still waiting their 5 s; a server stop cancels the wait, onDisable gives the money back
    private static final Map<Object, UUID> PAID = new ConcurrentHashMap<>();

    private final Player viewer;
    private final Inventory inventory;
    private final Map<Integer, Runnable> actions = new HashMap<>();
    private List<Entry> entries;
    private int page;
    private @Nullable String confirm;
    private long confirmUntil;
    private boolean busy;
    private long ignoreClicksUntil;

    private WarpView(Player viewer, List<Entry> entries) {
        this.viewer = viewer;
        this.entries = entries;
        this.inventory = Bukkit.createInventory(this, SIZE, Text.of("§5Warp-Menü"));
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    /** Main thread. The owners' names come from their player files: read on a worker first. */
    public static void open(@NotNull Player player) {
        Tasks.async(() -> {
            List<Entry> entries = entries(player.getUniqueId());
            MainThread.run(() -> {
                if (!player.isOnline()) return;
                WarpView view = new WarpView(player, entries);
                view.render();
                player.openInventory(view.inventory);
                player.playSound(player.getLocation(), Sound.BLOCK_SHULKER_BOX_OPEN, 1f, 1.4f);
            });
        });
    }

    // worker thread: the own warp first, then by name
    private static List<Entry> entries(UUID viewer) {
        List<Entry> entries = new ArrayList<>();
        for (UUID owner : WarpCache.getInstance().getWarpOwners()) {
            String name = Bukkit.getOfflinePlayer(owner).getName();
            entries.add(new Entry(owner, name == null ? "?" : name));
        }
        entries.sort(Comparator.comparing((Entry entry) -> !entry.owner().equals(viewer)).thenComparing(entry -> entry.name().toLowerCase()));
        return entries;
    }


    /* render */

    private void render() {
        actions.clear();
        ignoreClicksUntil = System.currentTimeMillis() + CLICK_COOLDOWN_MILLIS;
        if (confirm != null && System.currentTimeMillis() >= confirmUntil) confirm = null;
        ItemStack frame = Items.pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = 0; slot < SIZE; slot++) inventory.setItem(slot, frame);
        for (int slot : WARP_SLOTS) inventory.setItem(slot, null);
        set(SLOT_CLOSE, Items.named(Material.BARRIER, "§cSchließen", List.of()), () -> Tasks.sync(viewer::closeInventory));

        renderTeamWarp();
        renderWarps();
        renderOwnWarp();
    }

    // one field of the warp list with its click
    private record Tile(ItemStack item, Runnable action) {}

    private void renderWarps() {
        List<Tile> tiles = new ArrayList<>(partnerWarps());
        for (Entry entry : entries) {
            boolean own = entry.owner().equals(viewer.getUniqueId());
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(entry.owner()));
            meta.customName(Text.of(own ? "§aDein Warp" : "§fWarp von §a" + entry.name()));
            meta.lore(Text.lore(List.of("§7Teleport: §f" + TELEPORT_COST + " Schilling", "", "§e» Klicken zum Teleportieren")));
            head.setItemMeta(meta);
            tiles.add(new Tile(head, () -> teleport(() -> warpLocation(entry.owner()), own ? "zu deinem Warp" : "zum Warp von " + entry.name())));
        }
        int pages = Math.max(1, (tiles.size() + WARP_SLOTS.length - 1) / WARP_SLOTS.length);
        page = Math.clamp(page, 0, pages - 1);
        int start = page * WARP_SLOTS.length;
        for (int i = 0; i < WARP_SLOTS.length && start + i < tiles.size(); i++) {
            Tile tile = tiles.get(start + i);
            set(WARP_SLOTS[i], tile.item(), tile.action());
        }
        if (tiles.isEmpty()) inventory.setItem(22, Items.named(Material.PAPER, "§7Noch keine Warps", List.of()));
        inventory.setItem(SLOT_PAGE, Items.named(Material.PAPER, "§fSeite " + (page + 1) + " von " + pages, List.of()));
        if (page > 0) set(SLOT_PREVIOUS, Items.named(Material.ARROW, "§f◀ Zurück", List.of()), () -> {
            page--;
            render();
        });
        if (page < pages - 1) set(SLOT_NEXT, Items.named(Material.ARROW, "§fWeiter ▶", List.of()), () -> {
            page++;
            render();
        });
    }

    private void renderOwnWarp() {
        boolean hasWarp = WarpCache.getInstance().hasWarp(viewer.getUniqueId());
        set(SLOT_OWN_SET, Items.named(Material.NETHER_STAR, hasWarp ? "§bDeinen Warp hierher verschieben" : "§bDeinen Warp hier setzen", List.of(
                "§7Kostet §f" + Items.format(hasWarp ? MOVE_COST : CREATE_COST) + " Schilling",
                "§8Jeder Spieler kann deinen Warp nutzen.",
                "",
                "§e» Klicken")), () -> {
            Location here = viewer.getLocation();
            act(() -> hasWarp ? moveOwn(here) : createOwn(here));
        });
        if (hasWarp) {
            boolean asking = confirming("own");
            set(SLOT_OWN_REMOVE, Items.named(asking ? Material.RED_CONCRETE : Material.LAVA_BUCKET,
                    asking ? "§c§lWirklich löschen? Nochmal klicken" : "§cDeinen Warp löschen",
                    List.of("§7Das Geld gibt es nicht zurück.")), () -> {
                if (confirmed("own")) act(this::removeOwn);
            });
        }
    }

    private void renderTeamWarp() {
        TeamCacheObject team = CacheHandler.getInstance().getPlayerInCache(viewer).getTeamCacheObject();
        if (team == null || Teams.role(team, viewer.getUniqueId()) == Teams.Role.NONE) return;
        String colour = team.getTeamColor();
        boolean manager = Teams.role(team, viewer.getUniqueId()).canManage();
        if (team.getLevel() < Teams.WARP_LEVEL) {
            inventory.setItem(SLOT_TEAM, Items.named(Material.GRAY_DYE, colour + "Team-Warp", List.of("§7ab Team-Level " + Teams.WARP_LEVEL)));
            return;
        }
        TeamWarps.Warp warp = TeamWarps.get(team.getTeamID());
        if (warp == null) {
            inventory.setItem(SLOT_TEAM, Items.named(Material.GRAY_DYE, colour + "Team-Warp", List.of(
                    "§7Noch nicht gesetzt.", manager ? "§7Setzen: rechts daneben." : "§7Setzen können Boss und Vize.")));
        } else {
            set(SLOT_TEAM, Items.named(TeamCreateView.colourBanner(colour), colour + "§lTeam-Warp", List.of(
                    "§7" + worldLabel(warp.world()) + " §8· §f" + Math.round(warp.x()) + " " + Math.round(warp.y()) + " " + Math.round(warp.z()),
                    "§7Teleport: §f" + TELEPORT_COST + " Schilling",
                    "",
                    "§e» Klicken zum Teleportieren")), () -> teleport(() -> teamWarpLocation(), "zum Team-Warp"));
        }
        if (!manager) return;
        int cost = warp == null ? TeamWarps.SET_COST : TeamWarps.MOVE_COST;
        set(SLOT_TEAM_SET, Items.named(Material.RESPAWN_ANCHOR, warp == null ? colour + "Team-Warp hier setzen" : colour + "Team-Warp hierher verschieben", List.of(
                "§7Kostet §f" + Items.format(cost) + " Schilling §7aus der Team-Kasse",
                "§8Euer Team und eure Partner (ab Level " + Teams.WARP_LEVEL + ") nutzen ihn.",
                "",
                "§e» Klicken")), () -> {
            Location here = viewer.getLocation();
            act(() -> setTeamWarp(here));
        });
        if (warp != null) {
            boolean asking = confirming("team");
            set(SLOT_TEAM_REMOVE, Items.named(asking ? Material.RED_CONCRETE : Material.LAVA_BUCKET,
                    asking ? "§c§lWirklich löschen? Nochmal klicken" : "§cTeam-Warp löschen",
                    List.of("§7Das Geld gibt es nicht zurück.")), () -> {
                if (confirmed("team")) act(this::removeTeamWarp);
            });
        }
    }


    /* clicks */

    public void handleClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        event.setCancelled(true);
        if (event.getClickedInventory() != inventory || busy) return;
        if (System.currentTimeMillis() < ignoreClicksUntil) return;
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        Runnable action = actions.get(event.getRawSlot());
        if (action != null) action.run();
    }

    private void set(int slot, ItemStack item, @Nullable Runnable action) {
        inventory.setItem(slot, item);
        if (action != null) actions.put(slot, action);
    }

    private boolean confirming(String key) {
        return key.equals(confirm) && System.currentTimeMillis() < confirmUntil;
    }

    private boolean confirmed(String key) {
        if (confirming(key)) {
            confirm = null;
            return true;
        }
        confirm = key;
        confirmUntil = System.currentTimeMillis() + CONFIRM_MILLIS;
        viewer.playSound(viewer.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
        render();
        return false;
    }

    // a change on a worker, then the message and a fresh list on the main thread; the answer is a message (null: none)
    private void act(Supplier<String> change) {
        busy = true;
        UUID uuid = viewer.getUniqueId();
        Tasks.async(() -> {
            String message;
            try {
                message = change.get();
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().warn("Warp action of {} failed: {}", viewer.getName(), e.toString());
                message = "§cDas ging gerade nicht, versuch es gleich nochmal.";
            }
            String answer = message;
            List<Entry> fresh = entries(uuid);
            MainThread.run(() -> {
                busy = false;
                entries = fresh;
                if (answer != null) viewer.sendMessage(Main.getChatPrefix() + answer);
                viewer.playSound(viewer.getLocation(), answer != null && answer.startsWith("§a") ? Sound.BLOCK_END_PORTAL_FRAME_FILL : Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1.2f);
                if (viewer.getOpenInventory().getTopInventory().getHolder(false) == this) render();
            });
        });
    }

    // pays the teleport on a worker, then teleports after the usual 5 s; moving or leaving gives the money back
    private void teleport(Supplier<Location> target, String what) {
        busy = true;
        UUID uuid = viewer.getUniqueId();
        Tasks.async(() -> {
            boolean paid;
            try {
                paid = PlayerRepository.tryWithdrawMoney(uuid, TELEPORT_COST);
            } catch (RuntimeException e) {
                MainThread.run(() -> {
                    busy = false;
                    fail("§cDas ging gerade nicht, versuch es gleich nochmal.");
                });
                return;
            }
            if (!paid) {
                MainThread.run(() -> {
                    busy = false;
                    fail("Das Teleportieren kostet " + Main.getCurrencyName(TELEPORT_COST) + "§f.");
                });
                return;
            }
            MainThread.deliverOrRefund(uuid, TELEPORT_COST, () -> {
                busy = false;
                Location location = viewer.isOnline() ? target.get() : null;
                if (location == null) {
                    refund(uuid);
                    if (viewer.isOnline()) fail("§cDiesen Warp gibt es nicht mehr.");
                    return;
                }
                viewer.closeInventory();
                viewer.sendMessage(Main.getChatPrefix() + "§c-" + TELEPORT_COST + " Schilling §7» " + what);
                viewer.playSound(viewer.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                Object ticket = new Object();
                PAID.put(ticket, uuid);
                try {
                    new PlayerTeleport().teleportAfter(viewer, location, () -> {
                        if (PAID.remove(ticket) != null) refund(uuid);
                    }, () -> PAID.remove(ticket));
                } catch (IllegalPluginAccessException e) {
                    // server stop: refundPending gives it back
                }
            });
        });
    }

    /** onDisable, before the database closes: teleports that were paid but will not happen any more. */
    public static void refundPending() {
        for (UUID uuid : List.copyOf(PAID.values())) {
            try {
                PlayerRepository.addMoney(uuid, TELEPORT_COST);
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().error("Teleport price of {} could not be refunded at shutdown", uuid, e);
            }
        }
        PAID.clear();
    }

    private void refund(UUID uuid) {
        Tasks.async(() -> PlayerRepository.addMoney(uuid, TELEPORT_COST));
        if (viewer.isOnline()) viewer.sendMessage(Main.getChatPrefix() + "§fDu bekommst die " + Main.getCurrencyName(TELEPORT_COST) + " §fzurück.");
    }

    private void fail(String message) {
        viewer.sendMessage(Main.getChatPrefix() + message);
        viewer.playSound(viewer.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
    }


    /* changes (worker thread; the position was read on the main thread) */

    private String createOwn(Location here) {
        UUID uuid = viewer.getUniqueId();
        if (!PlayerRepository.tryWithdrawMoney(uuid, CREATE_COST)) return "Ein eigener Warp kostet " + Main.getCurrencyName(CREATE_COST) + "§f.";
        boolean created;
        try {
            created = WarpCache.getInstance().create(uuid, here);
        } catch (RuntimeException e) {
            PlayerRepository.addMoney(uuid, CREATE_COST);
            throw e;
        }
        if (!created) {
            PlayerRepository.addMoney(uuid, CREATE_COST);
            return "Du hast schon einen Warp.";
        }
        return "§aDein Warp ist gesetzt. §c-" + Items.format(CREATE_COST) + " Schilling §8(jeder kann ihn nutzen)";
    }

    private String moveOwn(Location here) {
        UUID uuid = viewer.getUniqueId();
        if (!PlayerRepository.tryWithdrawMoney(uuid, MOVE_COST)) return "Das Verschieben kostet " + Main.getCurrencyName(MOVE_COST) + "§f.";
        boolean moved;
        try {
            moved = WarpCache.getInstance().move(uuid, here);
        } catch (RuntimeException e) {
            PlayerRepository.addMoney(uuid, MOVE_COST);
            throw e;
        }
        if (!moved) {
            PlayerRepository.addMoney(uuid, MOVE_COST);
            return "Du hast gerade keinen Warp.";
        }
        return "§aDein Warp ist verschoben. §c-" + MOVE_COST + " Schilling";
    }

    private String removeOwn() {
        WarpCache.getInstance().remove(viewer.getUniqueId());
        return "§aDein Warp ist gelöscht.";
    }

    private String setTeamWarp(Location here) {
        TeamCacheObject team = CacheHandler.getInstance().getPlayerInCache(viewer).getTeamCacheObject();
        if (team == null) return "Du bist in keinem Team.";
        TeamWarps.Result result = TeamWarps.set(team, viewer.getUniqueId(), here);
        return switch (result.outcome()) {
            case OK -> "§aDer Team-Warp ist " + (result.cost() == TeamWarps.SET_COST ? "gesetzt" : "verschoben")
                    + ". §7(" + Items.format(result.cost()) + " aus der Team-Kasse)";
            case LEVEL -> "Den Team-Warp gibt es ab Team-Level " + Teams.WARP_LEVEL + ".";
            case NOT_ALLOWED -> "Den Team-Warp setzen nur Boss und Vize.";
            case INSUFFICIENT_FUNDS -> "In der Team-Kasse fehlt Geld §8(" + Items.format(result.cost()) + " Schilling)§f.";
        };
    }

    private String removeTeamWarp() {
        TeamCacheObject team = CacheHandler.getInstance().getPlayerInCache(viewer).getTeamCacheObject();
        if (team == null || !TeamWarps.remove(team, viewer.getUniqueId())) return "Der Team-Warp ließ sich nicht löschen.";
        return "§aDer Team-Warp ist gelöscht.";
    }


    /* helpers */

    private static @Nullable Location warpLocation(UUID owner) {
        WarpCacheObject warp = WarpCache.getInstance().getWarp(owner);
        if (warp == null) return null;
        World world = Bukkit.getWorld(warp.getWorldName());
        return world == null ? null : new Location(world, warp.getX(), warp.getY(), warp.getZ());
    }

    // the partners' team warps come first: both teams need WARP_LEVEL
    private List<Tile> partnerWarps() {
        TeamCacheObject team = CacheHandler.getInstance().getPlayerInCache(viewer).getTeamCacheObject();
        if (team == null || team.getLevel() < Teams.WARP_LEVEL || Teams.role(team, viewer.getUniqueId()) == Teams.Role.NONE) return List.of();
        List<Tile> tiles = new ArrayList<>();
        for (Relations.Relation partnership : Relations.of(team.getTeamID(), Relations.Kind.PARTNER)) {
            String other = partnership.other(team.getTeamID());
            TeamCacheObject partner = CacheHandler.getInstance().getTeamCacheObject(other);
            TeamWarps.Warp warp = TeamWarps.get(other);
            if (partner == null || partner.getLevel() < Teams.WARP_LEVEL || warp == null) continue;
            String name = partner.getTeamColor() + partner.getTeamName();
            tiles.add(new Tile(Items.named(TeamCreateView.colourBanner(partner.getTeamColor()), name + " §7Team-Warp", List.of(
                    "§a✦ Partner §8· §7" + worldLabel(warp.world()),
                    "§7Teleport: §f" + TELEPORT_COST + " Schilling",
                    "",
                    "§e» Klicken zum Teleportieren")), () -> teleport(() -> partnerWarpLocation(other), "zum Team-Warp von " + Text.strip(name))));
        }
        return tiles;
    }

    // main thread, at the moment of the teleport: still partners, both levels still high enough
    private @Nullable Location partnerWarpLocation(String other) {
        TeamCacheObject team = CacheHandler.getInstance().getPlayerInCache(viewer).getTeamCacheObject();
        TeamCacheObject partner = CacheHandler.getInstance().getTeamCacheObject(other);
        if (team == null || partner == null || Teams.role(team, viewer.getUniqueId()) == Teams.Role.NONE) return null;
        if (team.getLevel() < Teams.WARP_LEVEL || partner.getLevel() < Teams.WARP_LEVEL || !Relations.partners(team.getTeamID(), other)) return null;
        TeamWarps.Warp warp = TeamWarps.get(other);
        return warp == null ? null : warp.toLocation();
    }

    // main thread, at the moment of the teleport: still in the team, level still high enough
    private @Nullable Location teamWarpLocation() {
        TeamCacheObject team = CacheHandler.getInstance().getPlayerInCache(viewer).getTeamCacheObject();
        if (team == null || team.getLevel() < Teams.WARP_LEVEL || Teams.role(team, viewer.getUniqueId()) == Teams.Role.NONE) return null;
        TeamWarps.Warp warp = TeamWarps.get(team.getTeamID());
        return warp == null ? null : warp.toLocation();
    }

    private static String worldLabel(String world) {
        World target = Bukkit.getWorld(world);
        if (target == null) return world;
        return switch (target.getEnvironment()) {
            case NETHER -> "Nether";
            case THE_END -> "End";
            default -> "Oberwelt";
        };
    }

}
