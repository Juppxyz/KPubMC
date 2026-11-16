package xyz.jupp.minecraft.perf;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.jupp.minecraft.Main;
import xyz.jupp.minecraft.inventory.MainThread;
import xyz.jupp.minecraft.utils.Tasks;
import xyz.jupp.minecraft.utils.Text;

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One running performance measurement (/leistung start): what the server really does while people play, without an
 * upload anywhere. Three sources:
 * <ul>
 *     <li>every tick's duration from Paper's tick events (exact, no sampling), the slowest ticks with their time window;</li>
 *     <li>once a second, on an own thread, the CPU time of the Java process and the heap; on the main thread the players;</li>
 *     <li>a Java Flight Recorder recording inside the server process: where each thread spends its time (a stack sample
 *     every 10 ms), garbage collections and allocations. Only these few events are enabled, never the environment,
 *     system properties or command line (they could hold passwords). {@link PerfReport} later charges every sample to
 *     the plugin whose class (by its class loader) is closest to it on the stack, otherwise to a part of the server.</li>
 * </ul>
 * Main thread only, except where noted. Nothing slow runs on the main thread: the recording starts and stops on the
 * meter thread or a worker, the report is built on a worker. The recording costs about 1-2 % CPU.
 */
public final class PerfSession implements Listener {

    public static final int DEFAULT_SECONDS = 120;
    public static final int MIN_SECONDS = 10;
    public static final int MAX_SECONDS = 900;
    // a measurement stopped earlier than this gives no report (the numbers would be noise)
    private static final int REPORT_MIN_SECONDS = 5;
    // ticks longer than this are lag spikes (a tick may take 50 ms at 20 TPS)
    static final double SPIKE_MS = 50.0;
    // the slowest ticks kept with their time window (for the spike table)
    private static final int MAX_SPIKES = 200;
    // the report folder keeps this many reports and this many kept recordings (oldest go first)
    private static final int KEEP_REPORTS = 30;
    private static final int KEEP_RECORDINGS = 3;
    static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);
    static final Path FOLDER = Path.of("plugins", "kpub", "leistung");

    private static @Nullable PerfSession current;
    // set while KPubMC shuts down: a report being built gives up (read by the worker)
    static volatile boolean shuttingDown;

    final String startedBy;
    private final @Nullable UUID starterId;
    // who gets the report in the chat: the starter and whoever stopped it
    private final Set<UUID> recipients = new LinkedHashSet<>();
    final int seconds;
    final boolean keepRecording;
    final Instant startedAt = Instant.now();
    final LocalDateTime startedLocal = LocalDateTime.now();
    private final long startNanos = System.nanoTime();
    final Thread mainThread = Thread.currentThread();
    private final BossBar bar;
    private BukkitTask sampler;
    // set by stop(): the recording start that is still running on the meter thread closes it again
    private volatile boolean ended;

    // the recording, started on the meter thread (JFR's first start takes a while); null if it could not start
    private final CompletableFuture<Object> recording;
    volatile @Nullable String recorderProblem;

    // tick durations in ms, in order
    private double[] ticks = new double[4096];
    int tickCount;
    // the slowest ticks: start and end as System.nanoTime (no allocation per tick: the allocation samples would blame us)
    final List<long[]> spikes = new ArrayList<>();
    final List<Double> spikeMs = new ArrayList<>();
    private long tickStartNanos;

    // once a second on the meter thread (CPU load can block for many ms in native code, e.g. on Windows: never on the
    // main thread); read by the report worker only after the meter has terminated
    final List<Double> processCores = new ArrayList<>();
    final List<Double> systemCpu = new ArrayList<>();
    final List<Long> heapUsed = new ArrayList<>();
    private long lastProcessCpu = -1;
    private long lastMeterNanos;
    private final ScheduledExecutorService meter = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "KPubMC-Leistung");
        thread.setDaemon(true);
        return thread;
    });
    // once a second on the main thread
    final List<Integer> players = new ArrayList<>();

    // baselines for the deltas over the whole measurement
    private final long mainCpuStart;
    private final long allocatedStart;
    private final Map<String, long[]> gcStart = new LinkedHashMap<>();

    private PerfSession(@NotNull CommandSender starter, int seconds, boolean keepRecording) {
        this.startedBy = starter.getName();
        this.starterId = starter instanceof Player player ? player.getUniqueId() : null;
        if (starterId != null) recipients.add(starterId);
        this.seconds = seconds;
        this.keepRecording = keepRecording;
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        this.mainCpuStart = threadCpu(threads, mainThread);
        this.allocatedStart = totalAllocated(threads);
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcStart.put(gc.getName(), new long[]{gc.getCollectionCount(), gc.getCollectionTime()});
        }
        this.bar = BossBar.bossBar(Text.of("§6Leistungsmessung"), 0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        this.recording = CompletableFuture.supplyAsync(this::startRecording, meter);
    }

    public static @Nullable PerfSession current() {
        return current;
    }

    /** Starts a measurement; null if one is running already. */
    public static @Nullable PerfSession start(@NotNull CommandSender starter, int seconds, boolean keepRecording) {
        if (current != null) return null;
        PerfSession session = new PerfSession(starter, Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, seconds)), keepRecording);
        current = session;
        Bukkit.getPluginManager().registerEvents(session, Main.getInstance());
        session.sampler = Bukkit.getScheduler().runTaskTimer(Main.getInstance(), session::sample, 20L, 20L);
        session.meter.scheduleAtFixedRate(session::measure, 1, 1, TimeUnit.SECONDS);
        if (starter instanceof Player player) player.showBossBar(session.bar);
        return session;
    }

    /**
     * Ends the measurement (main thread). The report is written on a worker and sent to the starter, to whoever stopped
     * it and to the console. Too short: no report.
     */
    public void stop(@Nullable CommandSender stoppedBy) {
        if (current != this) return;
        current = null;
        ended = true;
        HandlerList.unregisterAll(this);
        if (sampler != null) sampler.cancel();
        meter.shutdown();
        if (stoppedBy instanceof Player player) recipients.add(player.getUniqueId());
        for (UUID uuid : recipients) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) player.hideBossBar(bar);
        }

        long elapsed = Duration.between(startedAt, Instant.now()).toSeconds();
        if (elapsed < REPORT_MIN_SECONDS || tickCount < 20) {
            discardRecording();
            tell("§7Messung abgebrochen §8– §7nach " + elapsed + " s ist sie zu kurz für einen Bericht.", stoppedBy);
            return;
        }
        // everything that needs the main thread is read now; stopping the recording writes its file, which can take a
        // moment, so that and the analysis happen on a worker
        Snapshot snapshot = snapshot();
        Main.getInstance().getSLF4JLogger().info("performance measurement finished, the report is being written");
        tell("§7Messung beendet, der Bericht wird erstellt …", stoppedBy);
        Tasks.async(() -> report(snapshot));
    }

    // worker: stop the recording, analyse, write, tell
    private void report(Snapshot snapshot) {
        Path file = null;
        try {
            // the meter's last numbers are visible after its termination
            meter.awaitTermination(5, TimeUnit.SECONDS);
            Object handle = recording.get(30, TimeUnit.SECONDS);
            file = Recorder.dump(handle, FOLDER.resolve("aufnahme-" + FILE_TIME.format(startedLocal) + ".jfr"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (recorderProblem == null) recorderProblem = "Aufnahme nicht beendet: " + e.getClass().getSimpleName();
            Main.getInstance().getSLF4JLogger().warn("flight recording could not be written", e);
        }
        // a kept recording may only hold our own events: another recording at the same time (e.g. one started with the
        // JDK's default settings) writes into the same files, with environment and system properties
        boolean keep = keepRecording && file != null && !Recorder.othersRunning();
        PerfReport report;
        try {
            report = PerfReport.build(this, snapshot, file, keepRecording && !keep && file != null);
        } catch (java.util.concurrent.CancellationException e) {
            // the server stops: no report
            return;
        } catch (Throwable e) {
            Main.getInstance().getSLF4JLogger().error("performance report failed", e);
            MainThread.run(() -> tell("§cDer Bericht konnte nicht erstellt werden, siehe Server-Log.", null));
            return;
        } finally {
            if (!keep) Recorder.delete(file);
            prune();
        }
        MainThread.run(() -> {
            for (String line : report.chat()) {
                for (UUID uuid : recipients) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null) player.sendMessage(Text.section(line));
                }
                Bukkit.getConsoleSender().sendMessage(Text.section(line));
            }
        });
    }
    /** Called from onDisable: a running measurement ends without a report, a report being built gives up. */
    public static void abort() {
        shuttingDown = true;
        PerfSession session = current;
        if (session == null) return;
        current = null;
        session.ended = true;
        HandlerList.unregisterAll(session);
        if (session.sampler != null) session.sampler.cancel();
        session.meter.shutdownNow();
        session.discardRecording();
    }

    // the recording is closed without writing anything (now, or by the meter thread once its start is done)
    private void discardRecording() {
        recording.thenAccept(Recorder::close);
    }

    // to the starter (if online), the one who stopped it, else the console
    private void tell(String line, @Nullable CommandSender stoppedBy) {
        Set<CommandSender> to = new LinkedHashSet<>();
        for (UUID uuid : recipients) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) to.add(player);
        }
        if (stoppedBy != null) to.add(stoppedBy);
        if (to.isEmpty()) to.add(Bukkit.getConsoleSender());
        for (CommandSender sender : to) sender.sendMessage(Text.section(Main.getChatPrefix() + line));
    }


    /* measuring */

    // meter thread
    private Object startRecording() {
        try {
            Files.createDirectories(FOLDER);
            // a safety net if the stop never comes: the recording ends by itself well after the planned end
            Object handle = Recorder.start(Duration.ofSeconds(seconds * 3L + 300));
            if (ended) {
                // stopped (or aborted) while JFR was starting
                Recorder.close(handle);
                return null;
            }
            return handle;
        } catch (Throwable e) {
            // no JFR in this Java (or no disk): ticks, CPU and RAM are still measured, only the breakdown is missing
            recorderProblem = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            Main.getInstance().getSLF4JLogger().warn("performance measurement without Java Flight Recorder: {}", recorderProblem);
            MainThread.run(() -> tell("§cOhne Aufschlüsselung nach Plugins: §7Java Flight Recorder ist nicht verfügbar ("
                    + recorderProblem + ").", null));
            return null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickStart(ServerTickStartEvent event) {
        tickStartNanos = System.nanoTime();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent event) {
        // the tick that ran /leistung start began before the measurement: not counted
        if (tickStartNanos == 0) return;
        double ms = event.getTickDuration();
        if (tickCount == ticks.length) ticks = Arrays.copyOf(ticks, ticks.length * 2);
        ticks[tickCount++] = ms;
        if (ms >= SPIKE_MS) keepSpike(ms, tickStartNanos, System.nanoTime());
    }

    // the slowest MAX_SPIKES ticks stay (the first ones are not necessarily the worst)
    private void keepSpike(double ms, long start, long end) {
        if (spikes.size() < MAX_SPIKES) {
            spikes.add(new long[]{start, end});
            spikeMs.add(ms);
            return;
        }
        int least = 0;
        for (int i = 1; i < spikeMs.size(); i++) if (spikeMs.get(i) < spikeMs.get(least)) least = i;
        if (spikeMs.get(least) < ms) {
            spikes.set(least, new long[]{start, end});
            spikeMs.set(least, ms);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // the progress bar again for the starter after a relog
        if (event.getPlayer().getUniqueId().equals(starterId)) event.getPlayer().showBossBar(bar);
    }

    /** The wall clock time of a System.nanoTime value taken during this measurement. */
    Instant instantOf(long nanos) {
        return startedAt.plusNanos(nanos - startNanos);
    }

    // meter thread: CPU (cores the Java process used in the last second, from its CPU time) and heap
    private void measure() {
        try {
            com.sun.management.OperatingSystemMXBean os = os();
            long now = System.nanoTime();
            if (os != null) {
                long cpu = os.getProcessCpuTime();
                if (cpu >= 0 && lastProcessCpu >= 0 && now > lastMeterNanos) {
                    processCores.add((cpu - lastProcessCpu) / (double) (now - lastMeterNanos));
                }
                lastProcessCpu = cpu;
                double system = os.getCpuLoad();
                if (system >= 0) systemCpu.add(system);
            }
            lastMeterNanos = now;
            heapUsed.add(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        } catch (RuntimeException e) {
            // a failing bean must not end the schedule
        }
    }

    // main thread, once a second: players, the boss bar, the end
    private void sample() {
        players.add(Bukkit.getOnlinePlayers().size());
        long elapsed = Duration.between(startedAt, Instant.now()).toSeconds();
        // by the wall clock, not by ticks: on a lagging server 20 ticks take longer than a second
        if (elapsed >= seconds) {
            stop(null);
            return;
        }
        Player starter = starterId == null ? null : Bukkit.getPlayer(starterId);
        if (starter != null) {
            bar.progress((float) Math.min(1.0, elapsed / (double) seconds));
            bar.name(Text.of("§6Leistungsmessung §f" + elapsed + "/" + seconds + " s §8| §7Ø §f"
                    + PerfReport.ms(recentAverage(200)) + " ms/Tick §8| §7" + players.getLast() + " Spieler"));
        }
    }

    // average of the last n ticks
    private double recentAverage(int n) {
        int from = Math.max(0, tickCount - n);
        if (tickCount == from) return 0;
        double sum = 0;
        for (int i = from; i < tickCount; i++) sum += ticks[i];
        return sum / (tickCount - from);
    }

    double[] ticks() {
        return Arrays.copyOf(ticks, tickCount);
    }

    /** What the server knows about itself at the end (main thread). */
    record Snapshot(Instant endedAt, long mainCpuNanos, long allocatedBytes, Map<String, long[]> gc, long heapMax,
                    long heapCommitted, int cores, List<WorldInfo> worlds, Map<String, Integer> entityTypes,
                    List<PluginInfo> plugins, String javaVersion, double[] tps) {}

    record WorldInfo(String name, int chunks, int entities, int tileEntities, int tickingTileEntities, int players) {}

    // a plugin and its jar, for the class loader -> plugin map the worker builds
    record PluginInfo(String name, @Nullable File jar) {}

    private Snapshot snapshot() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        Map<String, long[]> gc = new LinkedHashMap<>();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long[] before = gcStart.getOrDefault(bean.getName(), new long[2]);
            gc.put(bean.getName(), new long[]{bean.getCollectionCount() - before[0], bean.getCollectionTime() - before[1]});
        }
        List<WorldInfo> worlds = new ArrayList<>();
        Map<String, Integer> types = new HashMap<>();
        for (World world : Bukkit.getWorlds()) {
            worlds.add(new WorldInfo(world.getName(), world.getChunkCount(), world.getEntityCount(), world.getTileEntityCount(),
                    world.getTickableTileEntityCount(), world.getPlayerCount()));
            for (Entity entity : world.getEntities()) types.merge(entity.getType().name().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        List<PluginInfo> plugins = new ArrayList<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) plugins.add(new PluginInfo(plugin.getName(), jarOf(plugin)));
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long allocated = totalAllocated(threads);
        Snapshot result = new Snapshot(Instant.now(), Math.max(0, threadCpu(threads, mainThread) - mainCpuStart),
                allocated < 0 || allocatedStart < 0 ? -1 : allocated - allocatedStart, gc,
                memory.getHeapMemoryUsage().getMax(), memory.getHeapMemoryUsage().getCommitted(),
                Runtime.getRuntime().availableProcessors(), worlds, types, plugins,
                System.getProperty("java.version", "?"), Bukkit.getTPS());
        return result;
    }

    private static @Nullable File jarOf(Plugin plugin) {
        try {
            var source = plugin.getClass().getProtectionDomain().getCodeSource();
            if (source == null) return null;
            File file = new File(source.getLocation().toURI());
            return file.isFile() ? file : null;
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    private static long threadCpu(ThreadMXBean threads, Thread thread) {
        try {
            return threads.isThreadCpuTimeSupported() ? Math.max(0, threads.getThreadCpuTime(thread.threadId())) : 0;
        } catch (UnsupportedOperationException e) {
            return 0;
        }
    }

    // bytes all threads allocated so far, -1 if this JVM cannot tell
    private static long totalAllocated(ThreadMXBean threads) {
        try {
            if (threads instanceof com.sun.management.ThreadMXBean sun && sun.isThreadAllocatedMemorySupported()
                    && sun.isThreadAllocatedMemoryEnabled()) {
                return sun.getTotalThreadAllocatedBytes();
            }
        } catch (UnsupportedOperationException | LinkageError e) {
            // older JVM
        }
        return -1;
    }

    static @Nullable com.sun.management.OperatingSystemMXBean os() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean sun ? sun : null;
    }

    // worker: old reports and kept recordings beyond the limits go; only our own file names are touched
    private static void prune() {
        try (var files = Files.list(FOLDER)) {
            List<Path> all = files.filter(Files::isRegularFile).sorted().toList();
            List<Path> reports = all.stream().filter(path -> name(path).startsWith("bericht-") && name(path).endsWith(".md")).toList();
            for (int i = 0; i < reports.size() - KEEP_REPORTS; i++) Files.deleteIfExists(reports.get(i));
            List<Path> recordings = all.stream().filter(path -> name(path).startsWith("aufnahme-") && name(path).endsWith(".jfr")).toList();
            for (int i = 0; i < recordings.size() - KEEP_RECORDINGS; i++) Files.deleteIfExists(recordings.get(i));
        } catch (Exception e) {
            Main.getInstance().getSLF4JLogger().warn("old performance reports could not be removed", e);
        }
    }

    private static String name(Path path) {
        return path.getFileName().toString();
    }


    /**
     * Java Flight Recorder, behind its own class: a Java without the jdk.jfr module fails here (caught where it is
     * called), not when the plugin loads.
     */
    static final class Recorder {

        private Recorder() {}

        static Object start(Duration safetyNet) throws Exception {
            if (!jdk.jfr.FlightRecorder.isAvailable()) throw new IllegalStateException("Java Flight Recorder ist nicht verfügbar");
            jdk.jfr.Recording recording = new jdk.jfr.Recording();
            recording.setName("KPubMC-Leistung");
            // only what the report needs (no environment, properties or command line: they may hold secrets)
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
            recording.enable("jdk.GarbageCollection");
            recording.enable("jdk.ObjectAllocationSample").with("throttle", "150/s");
            recording.setToDisk(true);
            recording.setDuration(safetyNet);
            recording.setMaxSize(512L * 1024 * 1024);
            recording.start();
            return recording;
        }

        // stops the recording, writes it to the file and closes it; null without a recording
        static @Nullable Path dump(@Nullable Object handle, Path file) throws Exception {
            if (!(handle instanceof jdk.jfr.Recording recording)) return null;
            try {
                if (recording.getState() == jdk.jfr.RecordingState.RUNNING) recording.stop();
                recording.dump(file);
                return file;
            } finally {
                recording.close();
            }
        }

        // closes without writing anything
        static void close(@Nullable Object handle) {
            if (!(handle instanceof jdk.jfr.Recording recording)) return;
            try {
                recording.close();
            } catch (RuntimeException e) {
                Main.getInstance().getSLF4JLogger().warn("flight recording could not be closed", e);
            }
        }

        // another flight recording is active besides ours (its events would end up in our file)
        static boolean othersRunning() {
            try {
                for (jdk.jfr.Recording other : jdk.jfr.FlightRecorder.getFlightRecorder().getRecordings()) {
                    if (other.getState() == jdk.jfr.RecordingState.RUNNING && !"KPubMC-Leistung".equals(other.getName())) return true;
                }
                return false;
            } catch (Throwable e) {
                return true;
            }
        }

        static void delete(@Nullable Path file) {
            if (file == null) return;
            try {
                Files.deleteIfExists(file);
            } catch (Exception e) {
                Main.getInstance().getSLF4JLogger().warn("flight recording {} could not be deleted", file, e);
            }
        }
    }

}
