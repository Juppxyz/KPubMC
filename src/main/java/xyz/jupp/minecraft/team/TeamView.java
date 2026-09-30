package xyz.jupp.minecraft.team;

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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.cache.CacheHandler;
import xyz.jupp.minecraft.cache.ChunkCache;
import xyz.jupp.minecraft.cache.ChunkCacheObject;
import xyz.jupp.minecraft.cache.PlayerCacheObject;
import xyz.jupp.minecraft.cache.TeamCacheObject;
import xyz.jupp.minecraft.database.Database;
import xyz.jupp.minecraft.database.PlayerRepository;
import xyz.jupp.minecraft.database.TeamRepository;
import xyz.jupp.minecraft.economy.Market;
import xyz.jupp.minecraft.economy.Nomad;
import xyz.jupp.minecraft.economy.TaxClass;
import xyz.jupp.minecraft.economy.Taxes;
import xyz.jupp.minecraft.economy.TeamBank;
import xyz.jupp.minecraft.inventory.Items;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.utils.AreaOptionsEnum;
import xyz.jupp.minecraft.utils.JailHandler;
import xyz.jupp.minecraft.utils.Locations;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The team menu (/team): overview, members, team treasury, area and level in tabs, plus pages for one member and for
 * inviting. Clicks go by slot (each render registers its actions), never by an item's name. What is shown comes from
 * one load on a worker; every change runs on a worker and loads again. Only used on the main thread.
 */
public final class TeamView implements InventoryHolder {

    private enum Tab {
        OVERVIEW("Übersicht", Material.BOOK),
        MEMBERS("Mitglieder", Material.PLAYER_HEAD),
        TREASURY("Team-Kasse", Material.GOLD_INGOT),
        AREA("Gebiet", Material.GRASS_BLOCK),
        LEVEL("Level", Material.EXPERIENCE_BOTTLE),
        RELATIONS("Beziehungen", Material.SHIELD);

        private final String label;
        private final Material icon;

        Tab(String label, Material icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    private enum Page { TABS, MEMBER, INVITE, TEAMS, RELATION }

    private record Member(UUID uuid, String name, Teams.Role role, @Nullable Instant lastSeen, long weekPoints, long totalPoints) {}

    private record State(int points, long treasury, @Nullable Instant founded, List<Member> members, List<TeamBank.Entry> history,
                         long weekPoints, int racePlace, int rankPlace, int teamCount, long money) {}

    // what a change reports: a message (may be null) and a follow-up on the main thread (names, team messages)
    private record Feedback(boolean ok, @Nullable String message, @Nullable Runnable after) {
        static Feedback ok(@Nullable String message, @Nullable Runnable after) {
            return new Feedback(true, message, after);
        }

        static Feedback fail(String message) {
            return new Feedback(false, message, null);
        }
    }

    private static final int SIZE = 54;
    private static final int SLOT_CLOSE = 8;
    private static final int MARKER_ROW = 9;
    private static final int SLOT_BACK = 0;
    private static final int SLOT_INFO = 4;
    private static final int SLOT_LEAVE = 45;
    private static final int SLOT_TEAM = 49;
    private static final int[] MEMBER_SLOTS = {18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35};
    private static final int HISTORY_LINES = 10;
    private static final long CLICK_COOLDOWN_MILLIS = 300;
    private static final long CONFIRM_MILLIS = 5_000;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(Market.ZONE);

    private final Player viewer;
    private final String teamID;
    private final Inventory inventory;
    private final Map<Integer, Runnable> actions = new HashMap<>();
    private Tab tab = Tab.OVERVIEW;
    private Page page = Page.TABS;
    private @Nullable UUID selected;
    private @Nullable String selectedTeam;
    private int teamsPage;
    // an action waiting for its second click ("Wirklich? Nochmal klicken")
    private @Nullable String confirm;
    private long confirmUntil;
    private boolean busy;
    private long ignoreClicksUntil;
    private State state;

    private TeamView(Player viewer, String teamID, String title) {
        this.viewer = viewer;
        this.teamID = teamID;
        this.inventory = Bukkit.createInventory(this, SIZE, Text.of(title));
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }


    /* open / load */

    /** Main thread: opens the menu of the player's team. */
    public static void open(@NotNull Player player) {
        PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(player);
        TeamCacheObject team = pco.getTeamCacheObject();
        if (pco.getTeamID() == null || team == null) {
            player.sendMessage(Main.getChatPrefix() + "Du bist in keinem Team. Gründe eins mit §a/team neu <Name>§f.");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
            return;
        }
        TeamView view = new TeamView(player, team.getTeamID(), team.getTeamColor() + "§l" + team.getTeamName());
        UUID uuid = player.getUniqueId();
        String teamID = team.getTeamID();
        Tasks.async(() -> {
            State state = loadOrNull(teamID, uuid);
            MainThread.run(() -> {
                if (!player.isOnline()) return;
                if (state == null) {
                    player.sendMessage(Main.getChatPrefix() + "Das Team-Menü lädt gerade nicht, versuch es gleich nochmal.");
                    return;
                }
                view.state = state;
                view.render();
                player.openInventory(view.inventory);
                player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
            });
        });
    }

    // worker thread
    private static @Nullable State loadOrNull(String teamID, UUID viewer) {
        try {
            return load(teamID, viewer);
        } catch (RuntimeException e) {
            Main.getInstance().getSLF4JLogger().warn("Team menu of {} could not be loaded: {}", teamID, e.toString());
            return null;
        }
    }

    private static State load(String teamID, UUID viewer) {
        LocalDate week = Nomad.weekStart(LocalDate.now(Market.ZONE));
        Timestamp weekStart = Timestamp.from(week.atStartOfDay(Market.ZONE).toInstant());
        record Row(int points, long treasury, @Nullable Instant founded) {}
        Row row = Database.queryOne("SELECT points, treasury, created_at FROM teams WHERE team_id = ?", result -> {
            Timestamp created = result.getTimestamp(3);
            return new Row(result.getInt(1), result.getLong(2), created == null ? null : created.toInstant());
        }, teamID);
        List<Member> members = Database.query("""
                SELECT m.uuid, m.role, m.nickname, p.last_seen,
                       COALESCE(SUM(d.points) FILTER (WHERE d.created_at >= ?), 0),
                       COALESCE(SUM(d.points), 0)
                FROM team_members m
                LEFT JOIN players p ON p.uuid = m.uuid
                LEFT JOIN nomad_deliveries d ON d.team_id = m.team_id AND d.player_uuid = m.uuid
                WHERE m.team_id = ?
                GROUP BY m.uuid, m.role, m.nickname, p.last_seen""", result -> {
            Timestamp seen = result.getTimestamp(4);
            return new Member(result.getObject(1, UUID.class), result.getString(3), Teams.Role.of(result.getString(2)),
                    seen == null ? null : seen.toInstant(), result.getLong(5), result.getLong(6));
        }, weekStart, teamID);
        List<Nomad.RaceEntry> race = Nomad.standings(week);
        int racePlace = 0;
        long weekPoints = 0;
        for (int i = 0; i < race.size(); i++) {
            if (race.get(i).teamID().equals(teamID)) {
                racePlace = i + 1;
                weekPoints = race.get(i).points();
            }
        }
        List<TeamRepository.RankedTeam> ranking = TeamRepository.getRanking();
        int rankPlace = 0;
        for (int i = 0; i < ranking.size(); i++) {
            if (ranking.get(i).teamID().equals(teamID)) rankPlace = i + 1;
        }
        return new State(row == null ? 0 : row.points(), row == null ? 0 : row.treasury(), row == null ? null : row.founded(),
                members, TeamBank.history(teamID, HISTORY_LINES), weekPoints, racePlace, rankPlace, ranking.size(),
                PlayerRepository.getMoney(viewer));
    }

    private @Nullable TeamCacheObject team() {
        return CacheHandler.getInstance().getTeamCacheObject(teamID);
    }

    private boolean isOpen() {
        return viewer.isOnline() && viewer.getOpenInventory().getTopInventory().getHolder(false) == this;
    }


    /* render */

    private void render() {
        TeamCacheObject team = team();
        if (team == null || Teams.role(team, viewer.getUniqueId()) == Teams.Role.NONE) {
            // left or removed meanwhile
            Tasks.sync(viewer::closeInventory);
            return;
        }
        actions.clear();
        ignoreClicksUntil = System.currentTimeMillis() + CLICK_COOLDOWN_MILLIS;
        if (confirm != null && System.currentTimeMillis() >= confirmUntil) confirm = null;
        ItemStack frame = Items.pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = 0; slot < SIZE; slot++) inventory.setItem(slot, frame);
        set(SLOT_CLOSE, Items.named(Material.BARRIER, "§cSchließen", List.of()), () -> Tasks.sync(viewer::closeInventory));

        Teams.Role role = Teams.role(team, viewer.getUniqueId());
        switch (page) {
            case TABS -> {
                renderTabs();
                switch (tab) {
                    case OVERVIEW -> renderOverview(team, role);
                    case MEMBERS -> renderMembers(team, role);
                    case TREASURY -> renderTreasury(role);
                    case AREA -> renderArea(team, role);
                    case LEVEL -> renderLevel(team, role);
                    case RELATIONS -> renderRelations(team, role);
                }
                renderBottom(team, role);
            }
            case MEMBER -> renderMember(team, role);
            case INVITE -> renderInvite(team);
            case TEAMS -> renderTeams();
            case RELATION -> renderRelation(role);
        }
    }

    private void renderTabs() {
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            Tab target = tabs[i];
            boolean current = target == tab;
            set(i, Items.glow(Items.named(target.icon, (current ? "§a§l" : "§f§l") + target.label,
                    List.of(current ? "§a✔ Geöffnet" : "§e» Klicken zum Öffnen")), current), () -> show(target));
        }
        inventory.setItem(MARKER_ROW + tab.ordinal(), Items.named(Material.LIME_STAINED_GLASS_PANE, "§a▲ " + tab.label, List.of()));
    }

    private void renderBottom(TeamCacheObject team, Teams.Role role) {
        String colour = team.getTeamColor();
        set(SLOT_TEAM, Items.named(TeamCreateView.colourBanner(colour), colour + "§l" + team.getTeamName(), List.of(
                "§7Level §f" + team.getLevel() + " §8· §f" + Items.format(state.points()) + " §7Team-Punkte",
                "§7Deine Rolle: " + colour + role.label())), null);
        if (role == Teams.Role.OWNER) return;
        boolean asking = confirming("leave");
        set(SLOT_LEAVE, Items.named(asking ? Material.RED_CONCRETE : Material.DARK_OAK_DOOR,
                asking ? "§c§lWirklich verlassen? Nochmal klicken" : "§cTeam verlassen", List.of()), () -> {
            if (confirmed("leave")) act(this::leave);
        });
    }

    private void renderOverview(TeamCacheObject team, Teams.Role role) {
        String colour = team.getTeamColor();
        long online = state.members().stream().filter(member -> Bukkit.getPlayer(member.uuid()) != null).count();
        String boss = state.members().stream().filter(member -> member.role() == Teams.Role.OWNER).map(Member::name).findFirst().orElse("?");
        List<String> info = new ArrayList<>();
        info.add("§7Boss: " + colour + boss);
        info.add("§7Mitglieder: §f" + state.members().size() + " §8(" + online + " online)");
        int partnerCount = Relations.of(teamID, Relations.Kind.PARTNER).size();
        int warCount = Relations.of(teamID, Relations.Kind.WAR).size();
        if (partnerCount + warCount > 0) info.add("§a✦ " + partnerCount + " Partner §8· §c⚔ " + warCount + (warCount == 1 ? " Krieg" : " Kriege"));
        if (state.founded() != null) info.add("§7Gegründet: §f" + DATE.format(state.founded()));
        set(20, Items.named(TeamCreateView.colourBanner(colour), colour + "§l" + team.getTeamName(), info), () -> show(Tab.MEMBERS));

        int level = team.getLevel();
        List<String> levelLore = new ArrayList<>();
        levelLore.add("§7XP-Bonus: §f×" + String.valueOf(Teams.xpMultiplier(level)).replace('.', ','));
        levelLore.add(level >= Teams.MAX_LEVEL ? "§7Höchstes Level erreicht"
                : "§7Nächstes Level: §f" + Items.format(Teams.upgradeCost(level)) + " Team-Punkte");
        levelLore.add("");
        levelLore.add("§e» Klicken: alle Level");
        set(22, Items.named(Material.EXPERIENCE_BOTTLE, "§dLevel " + level, levelLore), () -> show(Tab.LEVEL));

        List<String> pointsLore = new ArrayList<>();
        pointsLore.add("§7Diese Woche bei Nomad: §a+" + Items.format(state.weekPoints()));
        pointsLore.add(state.racePlace() > 0 ? "§7Wochen-Rennen: §fPlatz " + state.racePlace() : "§7Wochen-Rennen: §fnoch nichts geliefert");
        if (state.rankPlace() > 0) pointsLore.add("§7Rangliste: §fPlatz " + state.rankPlace() + " von " + state.teamCount());
        set(24, Items.named(Material.GOLD_INGOT, "§6" + Items.format(state.points()) + " Team-Punkte", pointsLore), null);

        set(29, Items.named(Material.GOLD_BLOCK, "§eTeam-Kasse: §f" + Items.format(state.treasury()) + " Schilling",
                List.of("", "§e» Klicken zur Kasse")), () -> show(Tab.TREASURY));
        int claimed = Teams.claimedChunks(teamID);
        set(31, Items.named(Material.GRASS_BLOCK, "§aGebiet: §f" + claimed + " von " + Teams.chunkLimit(level) + " Chunks",
                List.of("", "§e» Klicken zum Gebiet")), () -> show(Tab.AREA));
        List<String> warpLines = new ArrayList<>();
        for (int number = 1; number <= TeamWarps.COUNT; number++) {
            TeamWarps.Warp warp = TeamWarps.get(teamID, number);
            int required = TeamWarps.requiredLevel(number);
            warpLines.add("§7" + TeamWarps.label(number) + ": §f" + (level < required ? "ab Level " + required
                    : warp == null ? "nicht gesetzt" : worldLabel(warp.world())));
        }
        warpLines.add("§8Im §a/warp§8-Menü.");
        set(33, Items.named(Material.ENDER_PEARL, "§5Team-Warps", warpLines), null);

        List<String> roleLore = new ArrayList<>();
        switch (role) {
            case OWNER -> roleLore.addAll(List.of("§7Du darfst alles: Rollen vergeben,", "§7Mitglieder entfernen, auszahlen,", "§7Chunks, Warp und Upgrades."));
            case VICE -> roleLore.addAll(List.of("§7Du darfst einladen, Mitglieder entfernen,", "§7auszahlen, Chunks, Warp und Upgrades."));
            default -> roleLore.addAll(List.of("§7Du kannst einzahlen und bei Nomad", "§7Punkte für dein Team sammeln."));
        }
        set(40, Items.named(role == Teams.Role.OWNER ? Material.DIAMOND_SWORD : Material.GOLDEN_SWORD,
                "§fDeine Rolle: " + colour + role.label(), roleLore), null);
    }

    private void renderMembers(TeamCacheObject team, Teams.Role role) {
        List<Member> members = sortedMembers();
        for (int i = 0; i < members.size() && i < MEMBER_SLOTS.length; i++) {
            Member member = members.get(i);
            boolean manageable = manageable(team, member);
            List<String> lore = memberLore(member);
            if (manageable) {
                lore.add("");
                lore.add("§e» Klicken zum Verwalten");
            }
            set(MEMBER_SLOTS[i], head(member, team.getTeamColor(), lore), manageable ? () -> {
                selected = member.uuid();
                page = Page.MEMBER;
                confirm = null;
                render();
            } : null);
        }
        if (members.size() > MEMBER_SLOTS.length) {
            inventory.setItem(36, Items.named(Material.PAPER, "§7… und " + (members.size() - MEMBER_SLOTS.length) + " weitere", List.of()));
        }
        if (role.canManage()) {
            set(40, Items.named(Material.WRITABLE_BOOK, "§aMitglied aufnehmen", List.of(
                    "§7Spieler ohne Team, die Einladungen",
                    "§7annehmen (§a/invites§7).",
                    "",
                    "§e» Klicken zum Auswählen")), () -> {
                page = Page.INVITE;
                render();
            });
        }
    }

    private void renderMember(TeamCacheObject team, Teams.Role role) {
        set(SLOT_BACK, Items.named(Material.ARROW, "§f◀ Zurück", List.of("§7zu den Mitgliedern")), this::back);
        Member member = selected == null ? null : state.members().stream().filter(m -> m.uuid().equals(selected)).findFirst().orElse(null);
        if (member == null) {
            inventory.setItem(22, Items.named(Material.BARRIER, "§7Nicht mehr im Team", List.of()));
            return;
        }
        inventory.setItem(SLOT_INFO, head(member, team.getTeamColor(), memberLore(member)));
        if (role == Teams.Role.OWNER && member.role() != Teams.Role.OWNER) {
            boolean vice = member.role() == Teams.Role.VICE;
            set(20, Items.named(vice ? Material.LEATHER_HELMET : Material.GOLDEN_HELMET, vice ? "§fZum Mitglied machen" : "§fZum Vize machen", List.of(
                    "§7Vizes dürfen einladen, Mitglieder entfernen,",
                    "§7auszahlen, Chunks, Warp und Upgrades.",
                    "",
                    "§e» Klicken")), () -> act(() -> toggleVice(member)));
        }
        if (Teams.mayKick(team, viewer.getUniqueId(), member.uuid())) {
            String key = "kick:" + member.uuid();
            boolean asking = confirming(key);
            set(24, Items.named(asking ? Material.RED_CONCRETE : Material.IRON_DOOR,
                    asking ? "§c§lWirklich entfernen? Nochmal klicken" : "§cAus dem Team entfernen",
                    List.of("§7" + member.name() + " verlässt das Team sofort.")), () -> {
                if (confirmed(key)) act(() -> kick(member));
            });
        }
    }

    private void renderInvite(TeamCacheObject team) {
        set(SLOT_BACK, Items.named(Material.ARROW, "§f◀ Zurück", List.of("§7zu den Mitgliedern")), this::back);
        inventory.setItem(SLOT_INFO, Items.named(Material.WRITABLE_BOOK, "§aMitglied aufnehmen", List.of(
                "§7Hier stehen Spieler ohne Team,",
                "§7die Einladungen annehmen (§a/invites§7).",
                "§7Ein Klick nimmt sie direkt auf.")));
        int slot = 18;
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (slot > 44) break;
            if (candidate.equals(viewer)) continue;
            PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(candidate);
            if (pco.getTeamID() != null || !pco.isTeamInvites()) continue;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(candidate);
            meta.customName(Text.of("§a" + candidate.getName()));
            meta.lore(Text.lore(List.of("§e» Klicken zum Aufnehmen")));
            head.setItemMeta(meta);
            UUID target = candidate.getUniqueId();
            set(slot++, head, () -> act(() -> invite(target)));
        }
        if (slot == 18) {
            inventory.setItem(31, Items.named(Material.BARRIER, "§7Gerade niemand da", List.of(
                    "§7Nur Spieler ohne Team, die mit",
                    "§a/invites §7Einladungen annehmen.")));
        }
    }

    // left in green: paying in (every member), right in orange: taking out (owner and vices), in the middle balance and history
    private static final int[] DEPOSIT_FRAME = {18, 19, 20, 21, 36, 37, 38, 39};
    private static final int[] WITHDRAW_FRAME = {23, 24, 25, 26, 41, 42, 43, 44};

    private void renderTreasury(Teams.Role role) {
        boolean manager = role.canManage();
        for (int slot : DEPOSIT_FRAME) inventory.setItem(slot, Items.pane(Material.LIME_STAINED_GLASS_PANE));
        for (int slot : WITHDRAW_FRAME) inventory.setItem(slot, Items.pane(manager ? Material.ORANGE_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE));

        set(22, Items.named(Material.GOLD_BLOCK, "§e§lTeam-Kasse: §f§l" + Items.format(state.treasury()) + " Schilling", List.of(
                "§7Links einzahlen, rechts auszahlen.",
                "§8Beim Auszahlen fällt die Steuer",
                "§8wie beim Abheben an.")), null);
        inventory.setItem(27, Items.named(Material.HOPPER, "§a§l⬇ Einzahlen", List.of(
                "§7Von deinem Konto in die Team-Kasse.",
                "§7Das kann jedes Mitglied.",
                "§7Dein Konto: §f" + Items.format(state.money()) + " Schilling")));
        inventory.setItem(35, Items.named(Material.DROPPER, (manager ? "§6§l" : "§7§l") + "⬆ Auszahlen", List.of(
                "§7Aus der Team-Kasse auf dein Konto.",
                "§7Das dürfen nur Boss und Vize.")));

        Material[] icons = {Material.GOLD_NUGGET, Material.GOLD_INGOT, Material.GOLD_BLOCK};
        for (int i = 0; i < TeamBank.AMOUNTS.length; i++) {
            int amount = TeamBank.AMOUNTS[i];
            boolean enough = state.money() >= amount;
            set(28 + i, Items.named(icons[i], "§a§l+" + Items.format(amount) + " §aeinzahlen", List.of(
                    enough ? "§7Dein Konto: §f" + Items.format(state.money()) : "§cSo viel hast du nicht auf dem Konto.",
                    "",
                    "§a» Klicken zum Einzahlen")), () -> act(() -> deposit(amount)));
            if (manager) {
                int tax = Taxes.taxOn(amount, TaxClass.STANDARD);
                set(32 + i, Items.named(icons[i], "§6§l-" + Items.format(amount) + " §6auszahlen", List.of(
                        "§7Du bekommst §f" + Items.format(amount - tax) + " §8(Steuer " + Items.format(tax) + ")",
                        state.treasury() >= amount ? "§7In der Kasse: §f" + Items.format(state.treasury()) : "§cSo viel ist nicht in der Kasse.",
                        "",
                        "§6» Klicken zum Auszahlen")), () -> act(() -> withdraw(amount)));
            } else {
                inventory.setItem(32 + i, Items.named(Material.GRAY_DYE, "§7Auszahlen: nur Boss und Vize", List.of()));
            }
        }

        List<String> history = new ArrayList<>();
        for (TeamBank.Entry entry : state.history()) {
            history.add((entry.amount() >= 0 ? "§a+" : "§c") + Items.format(entry.amount()) + " §7" + ledgerLabel(entry.kind())
                    + " §8· " + (entry.name() == null ? "ehemaliges Mitglied" : entry.name()) + " · " + Items.ago(entry.at()));
        }
        if (history.isEmpty()) history.add("§7Noch keine Buchungen.");
        set(31, Items.named(Material.WRITABLE_BOOK, "§fVerlauf", history), null);
    }

    private void renderArea(TeamCacheObject team, Teams.Role role) {
        Location here = viewer.getLocation();
        World world = here.getWorld();
        int chunkX = here.getBlockX() >> 4;
        int chunkZ = here.getBlockZ() >> 4;
        ChunkCacheObject claim = ChunkCache.getInstance().getClaim(world, chunkX, chunkZ);
        boolean own = claim != null && teamID.equals(claim.getTeamID());
        String blocked = world.getEnvironment() == World.Environment.THE_END ? "§cIm End kann man nichts beanspruchen."
                : Locations.isLocationASpawn(here) ? "§cDer Spawn kann nicht beansprucht werden." : null;
        String status;
        if (blocked != null) {
            status = blocked;
        } else if (claim == null) {
            status = "§afrei";
        } else if (own) {
            status = team.getTeamColor() + "euer Gebiet";
        } else {
            TeamCacheObject other = CacheHandler.getInstance().getTeamCacheObject(claim.getTeamID());
            status = "§cGebiet von " + (other == null ? "einem anderen Team" : other.getTeamColor() + other.getTeamName());
        }
        set(20, Items.named(Material.FILLED_MAP, "§fHier: Chunk " + chunkX + ", " + chunkZ, List.of(
                "§7" + worldLabel(world.getName()) + ": " + status)), null);

        int level = team.getLevel();
        int claimed = Teams.claimedChunks(teamID);
        int limit = Teams.chunkLimit(level);
        if (role.canManage() && blocked == null && claim == null) {
            if (claimed >= limit) {
                List<String> lore = new ArrayList<>();
                lore.add("§7" + claimed + " von " + limit + " Chunks");
                if (level < Teams.MAX_LEVEL) lore.add("§7Level " + (level + 1) + ": §f" + Teams.chunkLimit(level + 1) + " Chunks");
                set(22, Items.named(Material.BARRIER, "§cChunk-Limit erreicht", lore), null);
            } else {
                set(22, Items.named(TeamCreateView.colourBanner(team.getTeamColor()), "§aDiesen Chunk beanspruchen", List.of(
                        "§7Kostet §f" + Teams.CHUNK_COST + " Team-Punkte §8(ihr habt " + Items.format(state.points()) + ")",
                        "§7Chunks: §f" + claimed + " von " + limit,
                        "",
                        "§e» Klicken zum Beanspruchen")), () -> act(() -> claim(world.getName(), chunkX, chunkZ)));
            }
        }
        if (role.canManage() && own) {
            boolean asking = confirming("release");
            set(24, Items.named(asking ? Material.RED_CONCRETE : Material.SHEARS,
                    asking ? "§c§lWirklich freigeben? Nochmal klicken" : "§cDiesen Chunk freigeben",
                    List.of("§7Die Team-Punkte gibt es nicht zurück.")), () -> {
                if (confirmed("release")) act(() -> release(world.getName(), chunkX, chunkZ));
            });
        }
        List<String> limits = new ArrayList<>();
        for (int i = 1; i <= Teams.MAX_LEVEL; i++) limits.add((i == level ? "§a» " : "§7") + "Level " + i + ": " + Teams.chunkLimit(i) + " Chunks");
        limits.add("§8Chunks über dem Limit bleiben,");
        limits.add("§8neue gibt es erst wieder darunter.");
        set(31, Items.named(Material.MAP, "§fEure Chunks: " + claimed + " von " + limit, limits), null);

        toggle(team, role, 37, AreaOptionsEnum.MOB_GRIEFING, Teams.MOB_GRIEFING_LEVEL, Material.CREEPER_HEAD, "§fMob-Griefing",
                team.isZoneOptionMobDamage(), "§7AUS: Creeper & Co. zerstören nichts");
        toggle(team, role, 39, AreaOptionsEnum.PVP, Teams.PVP_LEVEL, Material.IRON_SWORD, "§fPvP",
                team.isZoneOptionPvP(), "§7AUS: hier wird niemand angegriffen");
        toggle(team, role, 41, AreaOptionsEnum.ALARM, Teams.ALARM_LEVEL, Material.BELL, "§fGebiets-Alarm",
                team.isZoneOptionAlarm(), "§7AN: Meldung, wenn Fremde reinkommen");
        toggle(team, role, 43, AreaOptionsEnum.INTERACTION, Teams.INTERACTION_LEVEL, Material.LEVER, "§fFremde Interaktionen",
                team.isZoneOptionInteract(), "§7AUS: Fremde können nichts benutzen");
    }

    private void toggle(TeamCacheObject team, Teams.Role role, int slot, AreaOptionsEnum option, int requiredLevel, Material icon,
                        String name, boolean on, String explanation) {
        boolean unlocked = team.getLevel() >= requiredLevel;
        List<String> lore = new ArrayList<>();
        lore.add("§7Gerade: " + (on ? "§aAN" : "§cAUS"));
        lore.add(explanation);
        if (!unlocked) {
            lore.add("§cab Level " + requiredLevel);
        } else if (role.canManage()) {
            lore.add("");
            lore.add("§e» Klicken zum Umschalten");
        } else {
            lore.add("§8Umschalten: Boss und Vize");
        }
        set(slot, Items.named(unlocked ? icon : Material.BARRIER, name, lore),
                unlocked && role.canManage() ? () -> act(() -> switchOption(option, requiredLevel)) : null);
    }

    private void renderLevel(TeamCacheObject team, Teams.Role role) {
        int level = team.getLevel();
        for (int i = 1; i <= Teams.MAX_LEVEL; i++) {
            boolean reached = i <= level;
            List<String> lore = new ArrayList<>(Teams.benefits(i));
            if (i > 1) lore.add("§8Kosten: " + Items.format(Teams.upgradeCost(i - 1)) + " Team-Punkte");
            inventory.setItem(19 + i, Items.glow(Items.named(reached ? Material.EXPERIENCE_BOTTLE : Material.GRAY_DYE,
                    (reached ? team.getTeamColor() : "§7") + "Level " + i + (i == level ? " §a(jetzt)" : ""), lore), i == level));
        }
        if (level >= Teams.MAX_LEVEL) {
            inventory.setItem(40, Items.named(Material.NETHER_STAR, "§6Höchstes Level erreicht", List.of()));
        } else {
            int cost = Teams.upgradeCost(level);
            List<String> lore = new ArrayList<>();
            lore.add("§7Kostet §f" + Items.format(cost) + " Team-Punkte");
            lore.add("§7Ihr habt §f" + Items.format(state.points()));
            if (role.canManage()) {
                lore.add("");
                lore.add("§e» Klicken zum Upgraden");
            } else {
                lore.add("§8Upgraden: Boss und Vize");
            }
            set(40, Items.named(role.canManage() ? Material.NETHER_STAR : Material.GRAY_DYE, "§a§lAuf Level " + (level + 1) + " upgraden", lore),
                    role.canManage() ? () -> act(this::upgrade) : null);
        }
        inventory.setItem(31, Items.named(Material.SKELETON_SKULL, "§fVorsicht", List.of(
                "§7Stirbt ein Mitglied durch einen Spieler,",
                "§7kostet das " + Teams.DEATH_COST + " Team-Punkte. Reichen sie nicht,",
                "§7fällt das Team eine Stufe und bekommt",
                "§7deren Preis als Punkte zurück.")));
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

    private void show(Tab target) {
        tab = target;
        page = Page.TABS;
        confirm = null;
        viewer.playSound(viewer.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.2f);
        render();
    }

    private void back() {
        backTo(Tab.MEMBERS);
    }

    private void backTo(Tab target) {
        page = Page.TABS;
        tab = target;
        confirm = null;
        render();
    }

    /* relations: partners and wars */

    private void renderRelations(TeamCacheObject team, Teams.Role role) {
        inventory.setItem(18, Items.named(Material.LIME_BANNER, "§a✦ Partner", List.of(
                "§7Teilen ihre Gebiete, kein PvP",
                "§7untereinander. Team-Warps gegenseitig,",
                "§7wenn beide Level " + Teams.WARP_LEVEL + " sind.")));
        List<Relations.Relation> partners = Relations.of(teamID, Relations.Kind.PARTNER);
        for (int i = 0; i < partners.size() && i < 7; i++) {
            String other = partners.get(i).other(teamID);
            List<String> lore = new ArrayList<>();
            lore.add("§7Partner seit §f" + DATE.format(partners.get(i).since()));
            lore.add(partnerWarpLine(team, other));
            relationTile(19 + i, other, lore, role);
        }
        if (partners.isEmpty()) inventory.setItem(19, Items.named(Material.GRAY_DYE, "§7Noch keine Partner", List.of()));
        if (partners.size() > 7) set(26, Items.named(Material.PAPER, "§7… und " + (partners.size() - 7) + " weitere", List.of("§e» Klicken: alle Teams")), this::openTeams);

        inventory.setItem(27, Items.named(Material.IRON_SWORD, "§c⚔ Kriege", List.of(
                "§7PvP ist zwischen euch überall an,",
                "§7auch in geschützten Gebieten.",
                "§7Endet nach 3 Tagen ohne Kill",
                "§7oder mit einem Friedensvertrag.")));
        List<Relations.Relation> wars = Relations.of(teamID, Relations.Kind.WAR);
        for (int i = 0; i < wars.size() && i < 7; i++) {
            Relations.Relation war = wars.get(i);
            relationTile(28 + i, war.other(teamID), warLore(war), role);
        }
        if (wars.isEmpty()) inventory.setItem(28, Items.named(Material.GRAY_DYE, "§7Kein Krieg", List.of()));
        if (wars.size() > 7) set(35, Items.named(Material.PAPER, "§7… und " + (wars.size() - 7) + " weitere", List.of("§e» Klicken: alle Teams")), this::openTeams);

        inventory.setItem(36, Items.named(Material.WRITABLE_BOOK, "§eAnfragen an euch", List.of("§7Partnerschaften und Friedensangebote.")));
        List<Relations.Request> incoming = Relations.incoming(teamID);
        for (int i = 0; i < incoming.size() && i < 6; i++) {
            Relations.Request request = incoming.get(i);
            relationTile(37 + i, request.from(), List.of(request.kind() == Relations.RequestKind.PARTNER
                    ? "§7möchte Partner werden" : "§7bietet euch Frieden an", "§8" + Items.ago(request.at())), role);
        }
        if (incoming.isEmpty()) inventory.setItem(37, Items.named(Material.GRAY_DYE, "§7Keine offenen Anfragen", List.of()));
        if (role.canManage()) {
            set(44, Items.named(Material.COMPASS, "§fAlle Teams", List.of(
                    "§7Partnerschaft anfragen",
                    "§7oder Krieg erklären.",
                    "",
                    "§e» Klicken zum Auswählen")), this::openTeams);
        }
    }

    private void openTeams() {
        page = Page.TEAMS;
        teamsPage = 0;
        confirm = null;
        render();
    }

    // a team in the relations tab: its banner; owner and vices open its page
    private void relationTile(int slot, String other, List<String> lines, Teams.Role role) {
        TeamCacheObject team = CacheHandler.getInstance().getTeamCacheObject(other);
        List<String> lore = new ArrayList<>(lines);
        if (role.canManage()) {
            lore.add("");
            lore.add("§e» Klicken zum Verwalten");
        }
        Material banner = team == null ? Material.WHITE_BANNER : TeamCreateView.colourBanner(team.getTeamColor());
        set(slot, Items.named(banner, Relations.label(other), lore), role.canManage() ? () -> openRelation(other) : null);
    }

    private List<String> warLore(Relations.Relation war) {
        String other = war.other(teamID);
        List<String> lore = new ArrayList<>();
        lore.add("§7seit §f" + DATE.format(war.since()) + " §8· §7erklärt von " + (war.declaredBy() == null ? "?" : Relations.label(war.declaredBy())));
        lore.add("§7letzter Kill: §f" + (war.lastKill().equals(war.since()) ? "noch keiner" : Items.ago(war.lastKill())));
        lore.add("§7endet ohne Kill in: §f" + until(war.quietEnd()));
        if (Relations.requested(other, teamID, Relations.RequestKind.PEACE)) lore.add("§aSie bieten euch Frieden an.");
        if (Relations.requested(teamID, other, Relations.RequestKind.PEACE)) lore.add("§7Ihr habt Frieden angeboten.");
        return lore;
    }

    private String partnerWarpLine(TeamCacheObject team, String other) {
        TeamCacheObject partner = CacheHandler.getInstance().getTeamCacheObject(other);
        if (partner == null) return "§7Team-Warp: §f-";
        if (team.getLevel() < Teams.WARP_LEVEL || partner.getLevel() < Teams.WARP_LEVEL) return "§7Team-Warp: §fab Level " + Teams.WARP_LEVEL + " bei beiden";
        for (int number = 1; number <= TeamWarps.COUNT; number++) {
            if (partner.getLevel() >= TeamWarps.requiredLevel(number) && TeamWarps.get(other, number) != null) return "§7Team-Warp: §fim §a/warp§f-Menü";
        }
        return "§7Team-Warp: §fnicht gesetzt";
    }

    private void openRelation(String other) {
        selectedTeam = other;
        page = Page.RELATION;
        confirm = null;
        render();
    }

    private void renderTeams() {
        set(SLOT_BACK, Items.named(Material.ARROW, "§f◀ Zurück", List.of("§7zu den Beziehungen")), () -> backTo(Tab.RELATIONS));
        inventory.setItem(SLOT_INFO, Items.named(Material.COMPASS, "§fAlle Teams", List.of("§7Wähle ein Team.")));
        List<TeamCacheObject> others = new ArrayList<>(CacheHandler.getInstance().getAllTeams());
        others.removeIf(other -> other.getTeamID().equals(teamID));
        // partners, wars and requests first, then by name
        others.sort(Comparator.comparing((TeamCacheObject other) -> relationLine(other.getTeamID()).startsWith("§7keine"))
                .thenComparing(other -> other.getTeamName().toLowerCase()));
        int perPage = 27;
        int pages = Math.max(1, (others.size() + perPage - 1) / perPage);
        teamsPage = Math.clamp(teamsPage, 0, pages - 1);
        if (pages > 1) {
            inventory.setItem(49, Items.named(Material.PAPER, "§fSeite " + (teamsPage + 1) + " von " + pages, List.of()));
            if (teamsPage > 0) set(48, Items.named(Material.ARROW, "§f◀ Zurück", List.of()), () -> {
                teamsPage--;
                render();
            });
            if (teamsPage < pages - 1) set(50, Items.named(Material.ARROW, "§fWeiter ▶", List.of()), () -> {
                teamsPage++;
                render();
            });
        }
        int slot = 18;
        for (TeamCacheObject other : others.subList(Math.min(others.size(), teamsPage * perPage), Math.min(others.size(), (teamsPage + 1) * perPage))) {
            String id = other.getTeamID();
            set(slot++, Items.named(TeamCreateView.colourBanner(other.getTeamColor()), other.getTeamColor() + other.getTeamName(), List.of(
                    relationLine(id),
                    "§7Level §f" + other.getLevel() + " §8· §f" + other.getMembersList().size() + " §7Mitglieder",
                    "",
                    "§e» Klicken")), () -> openRelation(id));
        }
        if (others.isEmpty()) inventory.setItem(31, Items.named(Material.BARRIER, "§7Es gibt noch keine anderen Teams", List.of()));
    }

    private String relationLine(String other) {
        Relations.Relation relation = Relations.between(teamID, other);
        if (relation != null) return relation.kind() == Relations.Kind.PARTNER ? "§a✦ Partner" : "§c⚔ Krieg";
        if (Relations.requested(other, teamID, Relations.RequestKind.PARTNER)) return "§emöchte Partner werden";
        if (Relations.requested(teamID, other, Relations.RequestKind.PARTNER)) return "§7Anfrage verschickt";
        return "§7keine Beziehung";
    }

    private void renderRelation(Teams.Role role) {
        set(SLOT_BACK, Items.named(Material.ARROW, "§f◀ Zurück", List.of("§7zu den Beziehungen")), () -> backTo(Tab.RELATIONS));
        String other = selectedTeam;
        TeamCacheObject target = other == null ? null : CacheHandler.getInstance().getTeamCacheObject(other);
        if (target == null) {
            inventory.setItem(22, Items.named(Material.BARRIER, "§7Dieses Team gibt es nicht mehr", List.of()));
            return;
        }
        Relations.Relation relation = Relations.between(teamID, other);
        List<String> info = new ArrayList<>();
        info.add(relationLine(other));
        if (relation != null && relation.kind() == Relations.Kind.WAR) info.addAll(warLore(relation));
        if (relation != null && relation.kind() == Relations.Kind.PARTNER) info.add("§7Partner seit §f" + DATE.format(relation.since()));
        info.add("§7Level §f" + target.getLevel() + " §8· §f" + target.getMembersList().size() + " §7Mitglieder");
        inventory.setItem(SLOT_INFO, Items.named(TeamCreateView.colourBanner(target.getTeamColor()), target.getTeamColor() + "§l" + target.getTeamName(), info));
        if (!role.canManage()) {
            inventory.setItem(22, Items.named(Material.GRAY_DYE, "§7Beziehungen ändern Boss und Vize", List.of()));
            return;
        }
        if (relation == null) {
            if (Relations.requested(other, teamID, Relations.RequestKind.PARTNER)) {
                set(20, Items.named(Material.LIME_CONCRETE, "§a§lPartnerschaft annehmen", PARTNER_LORE),
                        () -> act(() -> relationAct(() -> Relations.acceptPartnership(teamID, other), other)));
                set(22, Items.named(Material.RED_CONCRETE, "§cAblehnen", List.of()),
                        () -> act(() -> relationAct(() -> Relations.decline(teamID, other, Relations.RequestKind.PARTNER), other)));
            } else if (Relations.requested(teamID, other, Relations.RequestKind.PARTNER)) {
                set(20, Items.named(Material.GRAY_DYE, "§7Anfrage zurückziehen", List.of("§7Ihr wartet noch auf eine Antwort.")),
                        () -> act(() -> relationAct(() -> Relations.withdraw(teamID, other, Relations.RequestKind.PARTNER), other)));
            } else {
                List<String> lore = new ArrayList<>(PARTNER_LORE);
                lore.add("§7Beide Teams müssen zustimmen.");
                set(20, Items.named(Material.LIME_BANNER, "§aPartnerschaft anfragen", lore),
                        () -> act(() -> relationAct(() -> Relations.requestPartnership(teamID, other), other)));
            }
            String key = "war:" + other;
            boolean asking = confirming(key);
            set(24, Items.named(asking ? Material.RED_CONCRETE : Material.IRON_SWORD,
                    asking ? "§c§lWirklich Krieg erklären? Nochmal klicken" : "§cKrieg erklären", List.of(
                            "§7PvP ist zwischen euch dann überall an,",
                            "§7auch in geschützten Gebieten.",
                            "§7Kills bringen Team-Punkte vom Gegner.",
                            "§7Endet nach 3 Tagen ohne Kill",
                            "§7oder mit einem Friedensvertrag.")), () -> {
                if (confirmed(key)) act(() -> relationAct(() -> Relations.declareWar(teamID, other), other));
            });
        } else if (relation.kind() == Relations.Kind.PARTNER) {
            String key = "end:" + other;
            boolean asking = confirming(key);
            set(20, Items.named(asking ? Material.RED_CONCRETE : Material.SHEARS,
                    asking ? "§c§lWirklich beenden? Nochmal klicken" : "§cPartnerschaft beenden",
                    List.of("§7Dafür reicht eine Seite.")), () -> {
                if (confirmed(key)) act(() -> relationAct(() -> Relations.endPartnership(teamID, other), other));
            });
        } else if (Relations.requested(other, teamID, Relations.RequestKind.PEACE)) {
            set(20, Items.named(Material.LIME_CONCRETE, "§a§lFrieden annehmen", List.of("§7Der Krieg ist dann vorbei.")),
                    () -> act(() -> relationAct(() -> Relations.acceptPeace(teamID, other), other)));
            set(22, Items.named(Material.RED_CONCRETE, "§cAblehnen", List.of()),
                    () -> act(() -> relationAct(() -> Relations.decline(teamID, other, Relations.RequestKind.PEACE), other)));
        } else if (Relations.requested(teamID, other, Relations.RequestKind.PEACE)) {
            set(20, Items.named(Material.GRAY_DYE, "§7Friedensangebot zurückziehen", List.of("§7Ihr wartet noch auf eine Antwort.")),
                    () -> act(() -> relationAct(() -> Relations.withdraw(teamID, other, Relations.RequestKind.PEACE), other)));
        } else {
            set(20, Items.named(Material.WHITE_BANNER, "§aFrieden anbieten", List.of("§7Nimmt das andere Team an,", "§7ist der Krieg vorbei.")),
                    () -> act(() -> relationAct(() -> Relations.offerPeace(teamID, other), other)));
        }
    }

    private static final List<String> PARTNER_LORE = List.of(
            "§7Partner teilen ihre Gebiete und",
            "§7greifen sich nie an. Team-Warps",
            "§7gegenseitig, wenn beide Level " + Teams.WARP_LEVEL + " sind.");

    // worker thread: only owner and vices change relations, checked again at the click (the page may be old)
    private Feedback relationAct(Supplier<Relations.Result> change, String other) {
        TeamCacheObject team = team();
        if (team == null || !Teams.role(team, viewer.getUniqueId()).canManage()) return Feedback.fail("Beziehungen ändern nur Boss und Vize.");
        return relationFeedback(change.get(), other);
    }

    // worker thread: the answer to a relation change, with the announcement on the main thread
    private Feedback relationFeedback(Relations.Result result, String other) {
        String us = Relations.label(teamID);
        String them = Relations.label(other);
        return switch (result) {
            case REQUESTED -> Feedback.ok("Anfrage an " + them + " §fverschickt.",
                    () -> Teams.notifyTeam(other, teamInfo(other) + us + " §fmöchte mit euch Partner werden. §7(/team → Beziehungen)"));
            case PARTNERED -> Feedback.ok(null, () -> broadcast("§7✦ " + us + " §7und " + them + " §7sind jetzt Partner."));
            case ENDED -> Feedback.ok(null, () -> broadcast("§7✦ Die Partnerschaft zwischen " + us + " §7und " + them + " §7ist beendet."));
            case WAR -> Feedback.ok(null, () -> broadcast("§7⚔ " + us + " §7hat " + them + " §7den Krieg erklärt."));
            case PEACE_OFFERED -> Feedback.ok("Friedensangebot an " + them + " §fverschickt.",
                    () -> Teams.notifyTeam(other, teamInfo(other) + us + " §fbietet euch Frieden an. §7(/team → Beziehungen)"));
            case PEACE -> Feedback.ok(null, () -> broadcast("§7☮ " + us + " §7und " + them + " §7haben Frieden geschlossen."));
            case WITHDRAWN -> Feedback.ok("§7Zurückgezogen.", null);
            case DECLINED -> Feedback.ok("§7Abgelehnt.", () -> Teams.notifyTeam(other, teamInfo(other) + us + " §fhat eure Anfrage abgelehnt."));
            case NOT_POSSIBLE -> Feedback.fail("Das geht gerade nicht, schau nochmal in die Übersicht.");
        };
    }

    private static void broadcast(String message) {
        Bukkit.broadcast(Text.section(Main.getChatPrefix() + message));
    }

    private static String teamInfo(String team) {
        TeamCacheObject cached = CacheHandler.getInstance().getTeamCacheObject(team);
        return (cached == null ? "§a" : cached.getTeamColor()) + "Team-Info §8» ";
    }

    // "2 T 5 Std", "3 Std 10 Min"
    private static String until(Instant end) {
        java.time.Duration left = java.time.Duration.between(Instant.now(), end);
        if (left.isNegative()) return "gleich";
        return left.toDays() > 0 ? left.toDays() + " T " + left.toHoursPart() + " Std" : left.toHours() + " Std " + left.toMinutesPart() + " Min";
    }



    private boolean confirming(String key) {
        return key.equals(confirm) && System.currentTimeMillis() < confirmUntil;
    }

    // first click arms the action, the second within CONFIRM_MILLIS runs it
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

    // a change on a worker; then the message, the follow-up and a fresh load on the main thread
    private void act(Supplier<Feedback> change) {
        busy = true;
        UUID uuid = viewer.getUniqueId();
        Tasks.async(() -> {
            Feedback feedback;
            try {
                feedback = change.get();
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().warn("Team action of {} failed: {}", viewer.getName(), e.toString());
                feedback = Feedback.fail("Das ging gerade nicht, versuch es gleich nochmal.");
            }
            Feedback result = feedback;
            State fresh = loadOrNull(teamID, uuid);
            MainThread.run(() -> {
                busy = false;
                if (fresh != null) state = fresh;
                if (result.message() != null) viewer.sendMessage(Main.getChatPrefix() + result.message());
                viewer.playSound(viewer.getLocation(), result.ok() ? Sound.BLOCK_NOTE_BLOCK_PLING : Sound.BLOCK_NOTE_BLOCK_BASS, 1f, result.ok() ? 1.6f : 1f);
                if (result.after() != null) result.after().run();
                if (isOpen()) render();
            });
        });
    }


    /* changes (worker thread) */

    private Feedback deposit(int amount) {
        return switch (TeamBank.deposit(viewer.getUniqueId(), teamID, amount)) {
            case OK -> {
                String message = viewer.getName() + " §fhat §a" + Items.format(amount) + " Schilling §fin die Team-Kasse gelegt.";
                yield Feedback.ok(null, () -> Teams.notifyTeam(teamID, teamInfo() + "§a" + message));
            }
            case INSUFFICIENT_FUNDS -> Feedback.fail("So viel hast du nicht auf dem Konto.");
            case NOT_ALLOWED -> Feedback.fail("Du bist nicht mehr in diesem Team.");
        };
    }

    private Feedback withdraw(int amount) {
        TeamBank.Payout payout = TeamBank.withdraw(viewer.getUniqueId(), teamID, amount);
        return switch (payout.outcome()) {
            case OK -> {
                String message = viewer.getName() + " §fhat §e" + Items.format(amount) + " Schilling §faus der Team-Kasse genommen.";
                yield Feedback.ok("Du bekommst §a" + Items.format(payout.paid()) + " Schilling §8(Steuer: " + payout.tax() + ")",
                        () -> Teams.notifyTeam(teamID, teamInfo() + "§e" + message));
            }
            case INSUFFICIENT_FUNDS -> Feedback.fail("So viel ist nicht in der Team-Kasse.");
            case NOT_ALLOWED -> Feedback.fail("Auszahlen dürfen nur Boss und Vize.");
        };
    }

    private Feedback claim(String world, int chunkX, int chunkZ) {
        TeamCacheObject team = team();
        if (team == null) return Feedback.fail("Dein Team gibt es nicht mehr.");
        return switch (Teams.claim(team, viewer.getUniqueId(), world, chunkX, chunkZ)) {
            case OK -> {
                int claimed = Teams.claimedChunks(teamID);
                String message = viewer.getName() + " §ahat einen Chunk beansprucht §8(" + claimed + "/" + Teams.chunkLimit(team.getLevel()) + ")";
                yield Feedback.ok(null, () -> Teams.notifyTeam(teamID, teamInfo() + team.getTeamColor() + message));
            }
            case TAKEN -> Feedback.fail("§cDieser Chunk gehört schon jemandem.");
            case LIMIT -> Feedback.fail("§cEuer Chunk-Limit ist erreicht, mehr gibt es mit dem nächsten Level.");
            case NO_POINTS -> Feedback.fail("§cDafür fehlen eurem Team Punkte §8(" + Teams.CHUNK_COST + ")§c.");
            case NOT_ALLOWED -> Feedback.fail("Chunks beanspruchen dürfen nur Boss und Vize.");
        };
    }

    private Feedback release(String world, int chunkX, int chunkZ) {
        TeamCacheObject team = team();
        if (team == null || !Teams.release(team, viewer.getUniqueId(), world, chunkX, chunkZ)) return Feedback.fail("Der Chunk ließ sich nicht freigeben.");
        return Feedback.ok("§7Der Chunk ist wieder frei.", null);
    }

    private Feedback switchOption(AreaOptionsEnum option, int requiredLevel) {
        TeamCacheObject team = team();
        if (team == null || !Teams.role(team, viewer.getUniqueId()).canManage()) return Feedback.fail("Das dürfen nur Boss und Vize.");
        if (team.getLevel() < requiredLevel) return Feedback.fail("Dafür muss euer Team Level §a" + requiredLevel + " §fsein.");
        CacheHandler.getInstance().changeAreaOptions(team, option);
        return Feedback.ok(null, null);
    }

    private Feedback upgrade() {
        TeamCacheObject team = team();
        if (team == null) return Feedback.fail("Dein Team gibt es nicht mehr.");
        return switch (Teams.upgrade(team, viewer.getUniqueId())) {
            case OK -> Feedback.ok(null, () -> {
                Teams.notifyTeam(teamID, teamInfo() + "§aEuer Team ist jetzt Level " + team.getLevel() + "!");
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (teamID.equals(CacheHandler.getInstance().getPlayerInCache(online).getTeamID())) {
                        online.playSound(online.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
                    }
                }
            });
            case MAX -> Feedback.fail("Euer Team hat schon das höchste Level.");
            case NO_POINTS -> Feedback.fail("§cDafür fehlen eurem Team Punkte.");
            case NOT_ALLOWED -> Feedback.fail("Upgraden dürfen nur Boss und Vize.");
        };
    }

    private Feedback toggleVice(Member member) {
        TeamCacheObject team = team();
        Teams.Role now = team == null ? null : Teams.toggleVice(team, viewer.getUniqueId(), member.uuid());
        if (now == null) return Feedback.fail("Das darf nur der Boss.");
        String colour = team.getTeamColor();
        return Feedback.ok(member.name() + " §fist jetzt " + colour + now.label() + "§f.", () -> {
            Player target = Bukkit.getPlayer(member.uuid());
            if (target == null) return;
            target.sendMessage(teamInfo() + "§fDu bist jetzt " + colour + now.label() + "§f.");
            JailHandler.refreshPlayerName(target, CacheHandler.getInstance().getPlayerInCache(target));
        });
    }

    private Feedback kick(Member member) {
        TeamCacheObject team = team();
        if (team == null || !Teams.kick(team, viewer.getUniqueId(), member.uuid())) return Feedback.fail("Das darfst du nicht.");
        return Feedback.ok(null, () -> {
            back();
            Teams.notifyTeam(teamID, teamInfo() + "§f" + member.name() + " §fist nicht mehr im Team.");
            Player target = Bukkit.getPlayer(member.uuid());
            if (target == null) return;
            target.sendMessage(Main.getChatPrefix() + "Du wurdest aus dem Team " + team.getTeamColor() + team.getTeamName() + " §fentfernt.");
            target.playSound(target.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.6f, 1f);
            JailHandler.refreshPlayerName(target, CacheHandler.getInstance().getPlayerInCache(target));
        });
    }

    private Feedback invite(UUID targetID) {
        TeamCacheObject team = team();
        Player target = Bukkit.getPlayer(targetID);
        if (team == null || !Teams.role(team, viewer.getUniqueId()).canManage()) return Feedback.fail("Aufnehmen dürfen nur Boss und Vize.");
        if (target == null) return Feedback.fail("Der Spieler ist nicht mehr online.");
        PlayerCacheObject pco = CacheHandler.getInstance().getPlayerInCache(target);
        if (pco.getTeamID() != null || !pco.isTeamInvites() || !CacheHandler.getInstance().addPlayerToTeam(target, team)) {
            return Feedback.fail("Der Spieler nimmt gerade keine Einladungen an.");
        }
        return Feedback.ok(null, () -> {
            back();
            Teams.notifyTeam(teamID, teamInfo() + "§a" + target.getName() + " §fist jetzt in unserem Team!");
            target.sendMessage(Main.getChatPrefix() + "Du bist jetzt im Team " + team.getTeamColor() + team.getTeamName() + "§f. §7Alles Weitere mit §a/team§7.");
            target.playSound(target.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            JailHandler.refreshPlayerName(target, pco);
        });
    }

    private Feedback leave() {
        TeamCacheObject team = team();
        if (team == null) return Feedback.fail("Dein Team gibt es nicht mehr.");
        if (Teams.role(team, viewer.getUniqueId()) == Teams.Role.OWNER) return Feedback.fail("Als Boss kannst du das Team nicht verlassen.");
        CacheHandler.getInstance().removePlayerFromTeam(viewer, team);
        return Feedback.ok("Du hast das Team " + team.getTeamColor() + team.getTeamName() + " §fverlassen.", () -> {
            viewer.closeInventory();
            if (viewer.isOnline()) JailHandler.refreshPlayerName(viewer, CacheHandler.getInstance().getPlayerInCache(viewer));
            Teams.notifyTeam(teamID, teamInfo() + "§f" + viewer.getName() + " hat das Team verlassen.");
        });
    }


    /* helpers */

    private String teamInfo() {
        TeamCacheObject team = team();
        return (team == null ? "§a" : team.getTeamColor()) + "Team-Info §8» ";
    }

    private List<Member> sortedMembers() {
        List<Member> members = new ArrayList<>(state.members());
        members.sort(Comparator.comparing((Member member) -> member.role().ordinal())
                .thenComparing(member -> Bukkit.getPlayer(member.uuid()) == null)
                .thenComparing(member -> member.name().toLowerCase()));
        return members;
    }

    private boolean manageable(TeamCacheObject team, Member member) {
        UUID uuid = viewer.getUniqueId();
        boolean roleChange = Teams.role(team, uuid) == Teams.Role.OWNER && member.role() != Teams.Role.OWNER;
        return roleChange || Teams.mayKick(team, uuid, member.uuid());
    }

    private static List<String> memberLore(Member member) {
        List<String> lore = new ArrayList<>();
        lore.add("§7Rolle: §f" + member.role().label());
        lore.add(Bukkit.getPlayer(member.uuid()) != null ? "§a● online" : "§7zuletzt online: §f" + Items.ago(member.lastSeen()));
        lore.add("§7Nomad diese Woche: §a+" + Items.format(member.weekPoints()));
        lore.add("§7Nomad insgesamt: §a+" + Items.format(member.totalPoints()));
        return lore;
    }

    private static ItemStack head(Member member, String colour, List<String> lore) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(member.uuid()));
        String style = member.role() == Teams.Role.OWNER ? "§l" : member.role() == Teams.Role.VICE ? "§o" : "";
        meta.customName(Text.of(colour + style + member.name()));
        meta.lore(Text.lore(lore));
        head.setItemMeta(meta);
        return head;
    }

    private static String ledgerLabel(String kind) {
        return switch (kind) {
            case TeamBank.DEPOSIT -> "eingezahlt";
            case TeamBank.WITHDRAW -> "ausgezahlt";
            case TeamBank.WARP_SET -> "Team-Warp gesetzt";
            case TeamBank.WARP_MOVE -> "Team-Warp verschoben";
            default -> kind;
        };
    }

    static String worldLabel(String world) {
        World target = Bukkit.getWorld(world);
        if (target == null) return world;
        return switch (target.getEnvironment()) {
            case NETHER -> "Nether";
            case THE_END -> "End";
            default -> "Oberwelt";
        };
    }

}
