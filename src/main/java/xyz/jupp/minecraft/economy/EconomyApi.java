package xyz.jupp.minecraft.economy;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.api.CashNotes;
import xyz.jupp.minecraft.api.CasinoBank;
import xyz.jupp.minecraft.api.CasinoResult;
import xyz.jupp.minecraft.api.KPubEconomy;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.utils.PlayerSaves;
import xyz.jupp.minecraft.utils.Tasks;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The {@link KPubEconomy} service other plugins get (the casino plugin), registered in onEnable. The rules are in the api
 * package: cash is only read, notes only move between a player and the casino till ({@link Casino}), no account is
 * credited and nothing is printed that the till did not pay for (one player at most the daily payout limit); the
 * till's surplus above its start capital may go into the treasury. Notes are taken and handed out on the main thread
 * (like a deposit at Basil, the player file is written soon after), the till books on a worker, and every future
 * completes on the main thread.
 */
public final class EconomyApi implements KPubEconomy, CashNotes, CasinoBank {

    private static final int GAME_MAX = 32;
    private static final List<Integer> NOTE_VALUES = Arrays.stream(Cash.NOTES).boxed().toList();

    private EconomyApi() {}

    /** onEnable, after Casino.load: other plugins find the service from their own onEnable on. */
    public static void register(@NotNull Main plugin) {
        Bukkit.getServicesManager().register(KPubEconomy.class, new EconomyApi(), plugin, ServicePriority.Normal);
    }


    /* KPubEconomy */

    @Override
    public int apiVersion() {
        return API_VERSION;
    }

    @Override
    public @NotNull String currencyName() {
        return Main.getCurrencyName();
    }

    @Override
    public int season() {
        return Cash.season();
    }

    @Override
    public @NotNull CashNotes cash() {
        return this;
    }

    @Override
    public @NotNull CasinoBank casino() {
        return this;
    }


    /* CashNotes */

    @Override
    public @NotNull List<Integer> noteValues() {
        return NOTE_VALUES;
    }

    @Override
    public int valueOf(@Nullable ItemStack item) {
        return Cash.value(item);
    }

    @Override
    public boolean isOldNote(@Nullable ItemStack item) {
        return Cash.isOldSeason(item);
    }

    @Override
    public boolean looksLikeCash(@Nullable ItemStack item) {
        return Cash.isAnyCash(item);
    }

    @Override
    public long cashOf(@NotNull Player player) {
        mainThread();
        return Cash.total(player);
    }


    /* CasinoBank */

    @Override
    public long balance() {
        return Casino.balance();
    }

    @Override
    public long startCapital() {
        return Casino.startCapital();
    }

    @Override
    public boolean isOpen() {
        return !AdminSettings.stopped(AdminSettings.STOP_CASINO);
    }

    @Override
    public @NotNull CompletableFuture<CasinoResult> takeBet(@NotNull Player player, long amount, @NotNull String game) {
        mainThread();
        CasinoResult.Status refused = check(player, amount);
        if (refused == null && Cash.total(player) < amount) refused = CasinoResult.Status.NOT_ENOUGH_CASH;
        if (refused == null && !changeFits(player, amount)) refused = CasinoResult.Status.INVENTORY_FULL;
        if (refused != null) return CompletableFuture.completedFuture(result(refused, amount));
        // the notes leave the inventory right away (a second call finds them gone), before anything async
        if (!Cash.pay(player, (int) amount)) return CompletableFuture.completedFuture(result(CasinoResult.Status.NOT_ENOUGH_CASH, amount));
        PlayerSaves.soon(player);
        UUID uuid = player.getUniqueId();
        String label = label(game);
        CompletableFuture<CasinoResult> future = new CompletableFuture<>();
        worker(() -> {
            long left;
            try {
                left = Casino.bet(uuid, amount, label);
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().warn("Casino bet of {} by {} ({}) could not be booked, the cash goes back", amount, uuid, label, e);
                if (!toMain(() -> {
                    giveBack(uuid, amount, label);
                    future.complete(result(CasinoResult.Status.FAILED, amount));
                })) {
                    // never to the bank account instead: the API must not turn cash into account money
                    Main.getInstance().getSLF4JLogger().error("Casino bet of {} by {} ({}) could not be booked nor given back (server stop)", amount, uuid, label);
                    future.complete(result(CasinoResult.Status.FAILED, amount));
                }
                return;
            }
            finish(future, new CasinoResult(CasinoResult.Status.OK, amount, left));
        });
        return future;
    }

    @Override
    public @NotNull CompletableFuture<CasinoResult> payWin(@NotNull Player player, long amount, @NotNull String game) {
        mainThread();
        CasinoResult.Status refused = check(player, amount);
        if (refused != null) return CompletableFuture.completedFuture(result(refused, amount));
        UUID uuid = player.getUniqueId();
        String label = label(game);
        CompletableFuture<CasinoResult> future = new CompletableFuture<>();
        worker(() -> {
            long left;
            try {
                left = Casino.win(uuid, amount, label);
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().warn("Casino win of {} for {} ({}) could not be booked", amount, uuid, label, e);
                finish(future, result(CasinoResult.Status.FAILED, amount));
                return;
            }
            if (left < 0) {
                finish(future, result(CasinoResult.Status.CASINO_EMPTY, amount));
                return;
            }
            boolean handed = toMain(() -> {
                // the player who is online now (a Player object from before a logout is never saved again)
                Player online = Bukkit.getPlayer(uuid);
                if (online == null) {
                    worker(() -> {
                        Casino.winBack(uuid, amount, label);
                        finish(future, result(CasinoResult.Status.PLAYER_GONE, amount));
                    });
                    return;
                }
                Cash.give(online, amount);
                PlayerSaves.soon(online);
                future.complete(new CasinoResult(CasinoResult.Status.OK, amount, left));
            });
            if (!handed) {
                // KPubMC shut down before the hand-out: the win goes back into the till
                Casino.winBack(uuid, amount, label);
                future.complete(result(CasinoResult.Status.PLAYER_GONE, amount));
            }
        });
        return future;
    }


    @Override
    public long dailyPayoutLimit() {
        return Casino.dailyLimit();
    }

    @Override
    public @NotNull CompletableFuture<Long> payoutLeft(@NotNull Player player) {
        mainThread();
        UUID uuid = player.getUniqueId();
        CompletableFuture<Long> future = new CompletableFuture<>();
        worker(() -> {
            long left;
            try {
                left = Casino.payoutLeft(uuid);
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().warn("Casino payout limit of {} could not be read", uuid, e);
                left = 0;
            }
            long value = left;
            if (!toMain(() -> future.complete(value))) future.complete(value);
        });
        return future;
    }

    @Override
    public @NotNull CompletableFuture<CasinoResult> payToTreasury(long amount, @NotNull String reason) {
        mainThread();
        if (amount <= 0 || amount > MAX_AMOUNT) return CompletableFuture.completedFuture(result(CasinoResult.Status.INVALID_AMOUNT, amount));
        if (!isOpen()) return CompletableFuture.completedFuture(result(CasinoResult.Status.CLOSED, amount));
        String label = label(reason);
        CompletableFuture<CasinoResult> future = new CompletableFuture<>();
        worker(() -> {
            long left;
            try {
                left = Casino.toTreasury(amount, label);
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().warn("Casino payment of {} to the treasury ({}) could not be booked", amount, label, e);
                finish(future, result(CasinoResult.Status.FAILED, amount));
                return;
            }
            finish(future, left < 0 ? result(CasinoResult.Status.CASINO_EMPTY, amount) : new CasinoResult(CasinoResult.Status.OK, amount, left));
        });
        return future;
    }


    /* helpers */

    // what refuses a booking before anything moves, null if it may go on
    private @Nullable CasinoResult.Status check(Player player, long amount) {
        if (amount <= 0 || amount % 10 != 0 || amount > MAX_AMOUNT) return CasinoResult.Status.INVALID_AMOUNT;
        if (!isOpen()) return CasinoResult.Status.CLOSED;
        // also a Player object from before a relog: its inventory is no longer the player's
        if (Bukkit.getPlayer(player.getUniqueId()) != player) return CasinoResult.Status.PLAYER_GONE;
        return null;
    }

    // the change of a bet must fit into the slots the notes free up and the empty ones, never drop at the player's feet
    private static boolean changeFits(Player player, long amount) {
        long change = Cash.total(player) - amount;
        if (change <= 0) return true;
        int free = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.isEmpty() || Cash.value(stack) > 0) free++;
        }
        return Cash.notes(change).size() <= free;
    }

    private static CasinoResult result(CasinoResult.Status status, long amount) {
        return new CasinoResult(status, amount, Casino.balance());
    }

    /*
     * The booking: on a worker normally, right here while the server stops. The casino plugin is disabled before KPubMC
     * (it depends on it) and may still book in its onDisable (refunds); a task queued then would never start, Bukkit
     * cancels it with KPubMC's tasks, and the notes already taken would be gone without a booking.
     */
    private static void worker(Runnable task) {
        if (Bukkit.isStopping() || !Main.getInstance().isEnabled()) task.run();
        else Tasks.async(task);
    }

    // to the main thread (run right here if already on it); false if KPubMC shut down meanwhile and nothing ran
    private static boolean toMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return true;
        }
        return MainThread.run(task);
    }

    // completes on the main thread; right here if KPubMC shut down meanwhile (the casino is disabled by then anyway)
    private static void finish(CompletableFuture<CasinoResult> future, CasinoResult result) {
        if (!toMain(() -> future.complete(result))) future.complete(result);
    }

    /*
     * Main thread: a bet that could not be booked goes back to the player as notes. If they left meanwhile it is lost
     * and logged, like a failed deposit at Basil: never to the bank account, the API must not turn cash into account
     * money (a casino plugin could otherwise make bookings fail on purpose).
     */
    private static void giveBack(UUID uuid, long amount, String game) {
        Player online = Bukkit.getPlayer(uuid);
        if (online == null) {
            Main.getInstance().getSLF4JLogger().error("Casino bet of {} by {} ({}) could not be booked nor given back (player left)", amount, uuid, game);
            return;
        }
        Cash.give(online, amount);
        PlayerSaves.soon(online);
    }

    // letters, digits, space and _ . : - only (the log; a zero byte or control character would make every booking fail)
    private static String label(@Nullable String game) {
        if (game == null) return "?";
        String clean = game.replaceAll("[^\\p{L}\\p{N} _.:-]", "").strip();
        if (clean.isEmpty()) return "?";
        return clean.length() <= GAME_MAX ? clean : clean.substring(0, GAME_MAX);
    }

    private static void mainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("KPubEconomy: call this on the main thread");
    }

}
