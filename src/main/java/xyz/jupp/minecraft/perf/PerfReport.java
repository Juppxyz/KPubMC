package xyz.jupp.minecraft.perf;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Turns a finished {@link PerfSession} into a report (worker thread): a short summary for the chat and the whole thing
 * as a Markdown file in plugins/kpub/leistung.
 * <p>
 * Who used the main thread: the recording took a stack sample of every running thread every 10 ms. A main-thread
 * sample is charged to the plugin whose class is closest to the sample's leaf (its own code and every server call it
 * made); the class's plugin comes from its class loader, so a library a plugin shaded counts for that plugin only when
 * that plugin's copy ran. Without plugin code the sample goes to a part of the server (mob spawning, entities, chunks
 * ...). Samples × 10 ms ÷ ticks = milliseconds of main-thread work per tick, including the work Paper does between ticks
 * (player packets, chunk tasks); idle time between ticks is left out. It is a sample: small shares (under ~1 %) are rough.
 */
final class PerfReport {

    // the sampling period of the recording (PerfSession.Recorder)
    private static final double SAMPLE_MS = 10.0;
    private static final double TICK_BUDGET_MS = 50.0;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault());
    static final String OWN = "Messung selbst";

    /*
     * The parts of the server a sample without plugin code belongs to. A part counts if ANY frame of the stack matches
     * (a class or package prefix, or "Class#method"), the first part in this list wins: mob spawning that looks up
     * chunks is mob spawning, a hopper that loads a chunk is a block entity, the player's tick is the player even though
     * Paper runs it from the connection. Each part is something an admin can turn (spawn limits, tracking range,
     * hoppers, view distance ...).
     */
    private static final String[][] SERVER_PARTS = {
            {"Server: Mob-Spawning", "net.minecraft.world.level.NaturalSpawner"},
            {"Server: Entity-Tracking (Bewegungen an Spieler senden)", "net.minecraft.server.level.ChunkMap$TrackedEntity",
                    "net.minecraft.server.level.ServerEntity"},
            {"Server: Block-Entities (Trichter, Öfen, Spawner …)", "net.minecraft.world.level.block.entity.",
                    "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity",
                    "net.minecraft.world.level.chunk.LevelChunk$RebindableTickingBlockEntityWrapper",
                    "net.minecraft.world.level.Level#tickBlockEntities"},
            {"Server: Spieler (Ticks, Inventare, Fortschritte)", "net.minecraft.server.level.ServerPlayer#doTick",
                    "net.minecraft.server.level.ServerPlayer#tick", "net.minecraft.server.players.", "net.minecraft.server.PlayerAdvancements"},
            {"Server: Spieler-Pakete (Bewegung, Klicks, Chat)", "net.minecraft.server.network.", "net.minecraft.network.", "io.netty."},
            {"Server: Entities (Mobs, Tiere, Items, Loren)", "net.minecraft.server.level.ServerLevel#tickNonPassenger",
                    "net.minecraft.server.level.ServerLevel#tickPassenger", "net.minecraft.world.entity."},
            {"Server: Blöcke (Zufalls-Ticks, Redstone, Wachstum)", "net.minecraft.world.level.block.", "net.minecraft.world.ticks.",
                    "net.minecraft.world.level.redstone.", "net.minecraft.server.level.ServerLevel#tickChunk"},
            {"Server: Licht", "net.minecraft.world.level.lighting.", "ca.spottedleaf.moonrise.patches.starlight.",
                    "ca.spottedleaf.starlight."},
            {"Server: Chunk-System (laden, erzeugen, speichern)", "ca.spottedleaf.moonrise.", "net.minecraft.server.level.ChunkMap",
                    "net.minecraft.server.level.ServerChunkCache", "net.minecraft.server.level.ChunkHolder",
                    "net.minecraft.world.level.chunk.", "net.minecraft.world.level.levelgen.", "net.minecraft.world.level.biome.",
                    "io.papermc.paper.chunk."},
            {"Server: Plugin-Schnittstelle (Events, Aufgaben)", "org.bukkit.", "io.papermc.paper.", "com.destroystokyo.paper."},
            {"Server: Welt (Wetter, Dörfer, Raids, Sonstiges)", "net.minecraft.world.level.", "net.minecraft.server.level."},
    };
    private static final String SERVER_REST = "Server: Rest (Java, Sonstiges)";
    // the tick loop waiting between ticks (no work: left out of the shares)
    private static final String IDLE = "Leerlauf zwischen den Ticks";
    private static final Set<String> IDLE_METHODS = Set.of("runServer", "waitUntilNextTick", "managedBlock", "haveTime",
            "waitForTasks", "pollTask", "pollTaskInternal");

    private final List<String> chat = new ArrayList<>();

    private PerfReport() {}

    List<String> chat() {
        return chat;
    }

    static String ms(double value) {
        return String.format(Locale.GERMANY, value >= 100 ? "%.0f" : "%.1f", value);
    }

    private static String pct(double fraction) {
        return String.format(Locale.GERMANY, fraction * 100 >= 10 ? "%.0f %%" : "%.1f %%", fraction * 100);
    }

    private static String cores(double cores) {
        return String.format(Locale.GERMANY, "%.1f", cores);
    }

    private static String mb(double bytes) {
        return bytes >= 1024L * 1024 * 1024
                ? String.format(Locale.GERMANY, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
                : String.format(Locale.GERMANY, "%.0f MB", bytes / (1024.0 * 1024));
    }


    /* the analysis */

    // samples per owner and the hottest frames of each owner
    private static final class Tally {
        long total;
        final Map<String, long[]> owners = new HashMap<>();
        final Map<String, Map<String, long[]>> frames = new HashMap<>();

        void add(String owner, @Nullable String frame) {
            total++;
            owners.computeIfAbsent(owner, k -> new long[1])[0]++;
            if (frame != null) frames.computeIfAbsent(owner, k -> new HashMap<>()).computeIfAbsent(frame, k -> new long[1])[0]++;
        }

        List<Map.Entry<String, long[]>> ranked() {
            return sortedDesc(owners);
        }

        List<Map.Entry<String, long[]>> topFrames(String owner, int n) {
            List<Map.Entry<String, long[]>> list = sortedDesc(frames.getOrDefault(owner, Map.of()));
            return list.subList(0, Math.min(n, list.size()));
        }
    }

    private static List<Map.Entry<String, long[]>> sortedDesc(Map<String, long[]> map) {
        List<Map.Entry<String, long[]>> list = new ArrayList<>(map.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        return list;
    }

    private static <T> List<T> first(List<T> list, int n) {
        return list.subList(0, Math.min(n, list.size()));
    }

    /*
     * A frame's class -> its plugin, by the class loader JFR recorded for it: Paper names a plugin's loader after the
     * plugin's jar. A class of the server (or Java) has another loader and never counts for a plugin, even if a plugin
     * shaded the same library. Only for a plugin loader we cannot place by name, the jar contents decide.
     */
    private static final class Owners {
        final Map<String, String> jarNames = new HashMap<>();
        final Map<String, String> classes = new HashMap<>();
        final Map<String, String> cache = new HashMap<>();

        Owners(List<PerfSession.PluginInfo> plugins) {
            for (PerfSession.PluginInfo plugin : plugins) {
                if (plugin.jar() != null) jarNames.put(plugin.jar().getName(), plugin.name());
            }
        }

        // only needed for loaders without a known name (built on first use)
        private void indexJars(List<PerfSession.PluginInfo> plugins) {
            if (!classes.isEmpty()) return;
            for (PerfSession.PluginInfo plugin : plugins) {
                if (plugin.jar() == null) continue;
                try (java.util.jar.JarFile jar = new java.util.jar.JarFile(plugin.jar())) {
                    var entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        String path = entries.nextElement().getName();
                        if (!path.endsWith(".class") || path.startsWith("META-INF/")) continue;
                        classes.putIfAbsent(path.substring(0, path.length() - 6).replace('/', '.'), plugin.name());
                    }
                } catch (IOException e) {
                    // unreadable jar: its classes stay unknown
                }
            }
        }

        @Nullable String of(jdk.jfr.consumer.RecordedClass type, List<PerfSession.PluginInfo> plugins) {
            String name = type.getName();
            if (name.startsWith("xyz.jupp.minecraft.perf.")) return OWN;
            var loader = type.getClassLoader();
            String loaderName = loader == null ? null : loader.getName();
            String loaderType = loader == null || loader.getType() == null ? "" : loader.getType().getName();
            if (!loaderType.contains("PluginClassLoader")) {
                // the server's, Java's or a library loader: not a plugin's own code
                return null;
            }
            String key = loaderName + '|' + name;
            String hit = cache.get(key);
            if (hit != null) return hit;
            String owner = loaderName == null ? null : jarNames.get(loaderName);
            if (owner == null) {
                indexJars(plugins);
                int lambda = name.indexOf("$$Lambda");
                String plain = lambda > 0 ? name.substring(0, lambda) : name;
                owner = classes.get(plain);
                if (owner == null && plain.indexOf('$') > 0) owner = classes.get(plain.substring(0, plain.indexOf('$')));
                if (owner == null) owner = loaderName == null ? "Plugin (unbekannt)" : loaderName;
            }
            cache.put(key, owner);
            return owner;
        }
    }

    // frame "type#method" -> index into SERVER_PARTS, -1 for none
    private static final Map<String, Integer> PART_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    // {owner, hottest frame} of a sample without plugin code
    private static String[] serverPart(List<jdk.jfr.consumer.RecordedFrame> frames) {
        jdk.jfr.consumer.RecordedFrame leaf = null;
        for (jdk.jfr.consumer.RecordedFrame frame : frames) {
            if (frame.isJavaFrame()) {
                leaf = frame;
                break;
            }
        }
        if (leaf != null && leaf.getMethod().getType().getName().equals("net.minecraft.server.MinecraftServer")
                && IDLE_METHODS.contains(leaf.getMethod().getName())) {
            return new String[]{IDLE, shortName(leaf)};
        }
        int best = Integer.MAX_VALUE;
        jdk.jfr.consumer.RecordedFrame bestFrame = null;
        for (jdk.jfr.consumer.RecordedFrame frame : frames) {
            if (!frame.isJavaFrame()) continue;
            String type = frame.getMethod().getType().getName();
            int part = PART_CACHE.computeIfAbsent(type + '#' + frame.getMethod().getName(), PerfReport::partOf);
            if (part >= 0 && part < best) {
                best = part;
                // the hot spot: the first frame from the leaf that belongs to the winning part
                bestFrame = frame;
            }
        }
        if (bestFrame == null) return new String[]{SERVER_REST, leaf == null ? "?" : shortName(leaf)};
        for (jdk.jfr.consumer.RecordedFrame frame : frames) {
            if (!frame.isJavaFrame()) continue;
            String type = frame.getMethod().getType().getName();
            if (PART_CACHE.get(type + '#' + frame.getMethod().getName()) == best) {
                bestFrame = frame;
                break;
            }
        }
        return new String[]{SERVER_PARTS[best][0], shortName(bestFrame)};
    }

    private static int partOf(String typeAndMethod) {
        String type = typeAndMethod.substring(0, typeAndMethod.indexOf('#'));
        for (int index = 0; index < SERVER_PARTS.length; index++) {
            String[] part = SERVER_PARTS[index];
            for (int i = 1; i < part.length; i++) {
                String pattern = part[i];
                if (pattern.indexOf('#') >= 0 ? typeAndMethod.equals(pattern) : type.startsWith(pattern)) return index;
            }
        }
        return -1;
    }

    // the owner of one stack sample and its hottest frame: {owner, frame}
    private static String[] owner(List<jdk.jfr.consumer.RecordedFrame> frames, Owners owners, List<PerfSession.PluginInfo> plugins) {
        for (jdk.jfr.consumer.RecordedFrame frame : frames) {
            if (!frame.isJavaFrame()) continue;
            String owner = owners.of(frame.getMethod().getType(), plugins);
            if (owner != null) return new String[]{owner, shortName(frame)};
        }
        return serverPart(frames);
    }

    private static String shortName(jdk.jfr.consumer.RecordedFrame frame) {
        String type = frame.getMethod().getType().getName();
        int lambda = type.indexOf("$$Lambda");
        if (lambda > 0) type = type.substring(0, lambda) + "$lambda";
        int dot = type.lastIndexOf('.');
        return (dot < 0 ? type : type.substring(dot + 1)) + "." + frame.getMethod().getName();
    }

    // thread name without its running number ("Craft Scheduler Thread - 12" -> "Craft Scheduler Thread")
    private static String threadGroup(@Nullable String name) {
        if (name == null || name.isBlank()) return "?";
        return name.replaceAll("[\\s#-]*\\d+$", "").strip();
    }

    record Spike(Instant start, Instant end, double ms, Map<String, long[]> owners, double[] gcMs) {}


    /* building */

    static PerfReport build(@NotNull PerfSession session, @NotNull PerfSession.Snapshot snap, @Nullable Path jfr,
                            boolean keepRefused) throws IOException {
        double[] ticks = session.ticks();
        double[] sorted = ticks.clone();
        Arrays.sort(sorted);
        double sum = 0;
        int spikes = 0;
        for (double t : ticks) {
            sum += t;
            if (t >= PerfSession.SPIKE_MS) spikes++;
        }
        int tickCount = Math.max(1, ticks.length);
        double avg = ticks.length == 0 ? 0 : sum / ticks.length;
        double wallSeconds = Math.max(1, Duration.between(session.startedAt, snap.endedAt()).toMillis() / 1000.0);
        double tps = Math.min(20.0, ticks.length / wallSeconds);

        Tally main = new Tally();
        Tally others = new Tally();
        Map<String, long[]> threadGroups = new HashMap<>();
        Map<String, long[]> allocation = new HashMap<>();
        Set<Long> allocationThreads = new HashSet<>();
        long allocationTotal = 0;
        long gcCount = 0;
        double gcPauseMs = 0;
        double gcLongestMs = 0;
        long truncated = 0;
        long idle = 0;
        List<Spike> spikeList = new ArrayList<>();
        for (int i = 0; i < session.spikes.size(); i++) {
            long[] window = session.spikes.get(i);
            spikeList.add(new Spike(session.instantOf(window[0]), session.instantOf(window[1]), session.spikeMs.get(i),
                    new HashMap<>(), new double[1]));
        }
        spikeList.sort(Comparator.comparing(Spike::start));

        String breakdownProblem = null;
        if (jfr == null || !Files.isRegularFile(jfr)) {
            breakdownProblem = session.recorderProblem == null ? "keine Aufnahme" : session.recorderProblem;
        } else {
            Owners owners = new Owners(snap.plugins());
            long mainId = session.mainThread.threadId();
            long events = 0;
            try (jdk.jfr.consumer.RecordingFile file = new jdk.jfr.consumer.RecordingFile(jfr)) {
                while (file.hasMoreEvents()) {
                    if ((++events & 1023) == 0 && PerfSession.shuttingDown) throw new CancellationException("server stop");
                    jdk.jfr.consumer.RecordedEvent event = file.readEvent();
                    switch (event.getEventType().getName()) {
                        case "jdk.ExecutionSample" -> {
                            var thread = event.getThread("sampledThread");
                            var stack = event.getStackTrace();
                            if (thread == null || stack == null) continue;
                            String[] owner = owner(stack.getFrames(), owners, snap.plugins());
                            if (thread.getJavaThreadId() == mainId) {
                                if (owner[0].equals(IDLE)) {
                                    idle++;
                                    continue;
                                }
                                if (stack.isTruncated()) truncated++;
                                main.add(owner[0], owner[1]);
                                Spike spike = spikeAt(spikeList, event.getStartTime());
                                if (spike != null) spike.owners().computeIfAbsent(owner[0], k -> new long[1])[0]++;
                            } else {
                                others.add(owner[0], owner[1]);
                                threadGroups.computeIfAbsent(threadGroup(thread.getJavaName()), k -> new long[1])[0]++;
                            }
                        }
                        case "jdk.ObjectAllocationSample" -> {
                            // a thread's first sample carries everything it allocated since it started (gigabytes on the
                            // main thread), not since the recording started: it would blame whoever was sampled first
                            var allocator = event.getThread("eventThread");
                            if (allocator == null || allocationThreads.add(allocator.getJavaThreadId())) continue;
                            long weight = event.getLong("weight");
                            var stack = event.getStackTrace();
                            String owner = stack == null ? SERVER_REST : owner(stack.getFrames(), owners, snap.plugins())[0];
                            allocation.computeIfAbsent(owner, k -> new long[1])[0] += weight;
                            allocationTotal += weight;
                        }
                        case "jdk.GarbageCollection" -> {
                            gcCount++;
                            double pause = event.getDuration("sumOfPauses").toNanos() / 1e6;
                            gcPauseMs += pause;
                            gcLongestMs = Math.max(gcLongestMs, event.getDuration("longestPause").toNanos() / 1e6);
                            // a GC pause inside a slow tick explains (part of) it
                            Spike spike = spikeAt(spikeList, event.getStartTime());
                            if (spike != null) spike.gcMs()[0] += pause;
                        }
                        default -> {
                        }
                    }
                }
            } catch (CancellationException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                // a damaged recording: the tick, CPU and RAM numbers stand anyway
                breakdownProblem = "Aufnahme nicht lesbar (" + e.getClass().getSimpleName() + ")";
                main = new Tally();
                others = new Tally();
                allocation.clear();
                allocationTotal = 0;
            }
        }
        boolean recorded = breakdownProblem == null;

        PerfReport report = new PerfReport();
        StringBuilder md = new StringBuilder();
        String when = PerfSession.FILE_TIME.format(session.startedLocal);

        // overview
        double peak = sorted.length == 0 ? 0 : sorted[sorted.length - 1];
        double coresAvg = average(session.processCores);
        double coresMax = session.processCores.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double systemAvg = average(session.systemCpu);
        double mainCpu = snap.mainCpuNanos() / 1e9 / wallSeconds;
        double heapAvg = session.heapUsed.stream().mapToLong(Long::longValue).average().orElse(0);
        double heapPeak = session.heapUsed.stream().mapToLong(Long::longValue).max().orElse(0);
        int playersMin = session.players.stream().mapToInt(Integer::intValue).min().orElse(0);
        int playersMax = session.players.stream().mapToInt(Integer::intValue).max().orElse(0);
        double playersAvg = session.players.stream().mapToInt(Integer::intValue).average().orElse(0);
        long gcMxCount = 0;
        long gcMxMs = 0;
        for (long[] gc : snap.gc().values()) {
            gcMxCount += gc[0];
            gcMxMs += gc[1];
        }
        // main-thread work per tick from the samples (in and between ticks, without idle)
        double sampledPerTick = main.total * SAMPLE_MS / tickCount;

        report.chat.add("§8§m          §r §6§lLeistungsmessung §8§m          ");
        report.chat.add("§7" + Math.round(wallSeconds) + " s, Ø §f" + Math.round(playersAvg) + " §7Spieler (" + playersMin + "–"
                + playersMax + "), §f" + ticks.length + " §7Ticks");
        report.chat.add("§7Tick: Ø §f" + ms(avg) + " ms §7· 95 % unter §f" + ms(percentile(sorted, 0.95)) + " ms §7· max §f"
                + ms(peak) + " ms" + (spikes > 0 ? " §7· §c" + spikes + "× über 50 ms" : ""));
        report.chat.add("§7TPS §f" + String.format(Locale.GERMANY, "%.2f", tps) + " §7· Tick-Budget belegt: §f" + pct(avg / TICK_BUDGET_MS));
        report.chat.add("§7CPU: Java nutzt Ø §f" + cores(coresAvg) + " §7von " + snap.cores() + " Kernen (max " + cores(coresMax)
                + ") · Haupt-Thread §f" + pct(mainCpu) + " §7eines Kerns");
        report.chat.add("§7RAM: Ø §f" + mb(heapAvg) + " §7(Spitze " + mb(heapPeak) + ") von §f" + mb(snap.heapMax()) + " §7· GC "
                + (recorded ? gcCount + "×, Pausen " + ms(gcPauseMs) + " ms (längste " + ms(gcLongestMs) + " ms)"
                        : gcMxCount + "×, " + gcMxMs + " ms")
                + (snap.allocatedBytes() > 0 ? " · " + mb(snap.allocatedBytes() / wallSeconds) + "/s" : ""));

        md.append("# Leistungsmessung ").append(when.replace('_', ' ')).append("\n\n");
        md.append("Gestartet von ").append(session.startedBy).append(", ").append(Math.round(wallSeconds)).append(" s, Java ")
                .append(snap.javaVersion()).append(", ").append(snap.cores()).append(" Kerne.\n\n");
        md.append("## Überblick\n\n| | |\n|---|---|\n");
        md.append("| Spieler | Ø ").append(String.format(Locale.GERMANY, "%.1f", playersAvg)).append(" (").append(playersMin)
                .append("–").append(playersMax).append(") |\n");
        md.append("| Ticks | ").append(ticks.length).append(", TPS ").append(String.format(Locale.GERMANY, "%.2f", tps))
                .append(" (Paper 1 min: ").append(String.format(Locale.GERMANY, "%.2f", Math.min(20, snap.tps()[0]))).append(") |\n");
        md.append("| Tick-Zeit | Ø ").append(ms(avg)).append(" ms, Median ").append(ms(percentile(sorted, 0.5)))
                .append(" ms, 95 % ").append(ms(percentile(sorted, 0.95))).append(" ms, 99 % ").append(ms(percentile(sorted, 0.99)))
                .append(" ms, max ").append(ms(peak)).append(" ms |\n");
        md.append("| Tick-Budget (50 ms) | ").append(pct(avg / TICK_BUDGET_MS)).append(" belegt |\n");
        md.append("| Lag-Spitzen (≥ 50 ms) | ").append(spikes).append(" |\n");
        md.append("| CPU Java-Prozess | Ø ").append(cores(coresAvg)).append(", max ").append(cores(coresMax)).append(" von ")
                .append(snap.cores()).append(" Kernen |\n");
        md.append("| CPU System gesamt | Ø ").append(pct(systemAvg)).append(" (alles auf dem Rechner bzw. Container) |\n");
        md.append("| Haupt-Thread | ").append(pct(mainCpu)).append(" eines Kerns |\n");
        md.append("| Heap | Ø ").append(mb(heapAvg)).append(", Spitze ").append(mb(heapPeak)).append(", reserviert ")
                .append(mb(snap.heapCommitted())).append(", max ").append(mb(snap.heapMax())).append(" |\n");
        md.append("| Garbage Collection | ").append(recorded ? gcCount + " Läufe, Pausen zusammen " + ms(gcPauseMs) + " ms, längste "
                + ms(gcLongestMs) + " ms" : gcMxCount + " Läufe, " + gcMxMs + " ms").append(" |\n");
        if (snap.allocatedBytes() > 0) {
            md.append("| Speicher-Umsatz | ").append(mb(snap.allocatedBytes() / wallSeconds)).append("/s (neu angelegte Objekte) |\n");
        }
        md.append('\n');

        // histogram
        int[] buckets = new int[6];
        String[] bucketNames = {"unter 10 ms", "10–20 ms", "20–30 ms", "30–40 ms", "40–50 ms", "50 ms und mehr"};
        for (double t : ticks) buckets[Math.min(5, (int) (t / 10))]++;
        md.append("### Verteilung der Tick-Zeiten\n\n| Tick-Zeit | Anzahl | Anteil |\n|---|---|---|\n");
        for (int i = 0; i < buckets.length; i++) {
            md.append("| ").append(bucketNames[i]).append(" | ").append(buckets[i]).append(" | ")
                    .append(pct(ticks.length == 0 ? 0 : buckets[i] / (double) ticks.length)).append(" |\n");
        }
        md.append('\n');

        if (!recorded) {
            report.chat.add("§cOhne Aufschlüsselung nach Plugins: §7" + breakdownProblem + ".");
            md.append("## Aufschlüsselung\n\nNicht möglich: ").append(breakdownProblem).append(".\n\n");
        } else {
            // main thread by owner
            report.chat.add("§6Haupt-Thread §7(ms Arbeit pro Tick, Anteil am 50-ms-Budget):");
            md.append("## Haupt-Thread nach Verursacher\n\n");
            md.append("Jede Stichprobe (alle 10 ms) zählt für das Plugin, dessen Code ihr auf dem Stapel am nächsten ist, sonst für ")
                    .append("einen Teil des Servers. ms pro Tick = Stichproben × 10 ms ÷ Ticks; das umfasst auch die Arbeit, die Paper ")
                    .append("zwischen den Ticks erledigt (Spieler-Pakete, Chunk-Aufgaben). ")
                    .append(main.total).append(" Stichproben mit Arbeit = ").append(ms(sampledPerTick)).append(" ms pro Tick")
                    .append(idle > 0 ? "; " + idle + " im Leerlauf zwischen den Ticks, nicht mitgezählt" : "").append(".\n\n");
            md.append("| Verursacher | Anteil | ms pro Tick | vom Budget | Stellen mit der meisten Zeit |\n|---|---|---|---|---|\n");
            int shown = 0;
            for (Map.Entry<String, long[]> entry : main.ranked()) {
                long samples = entry.getValue()[0];
                double share = samples / (double) Math.max(1, main.total);
                double perTick = samples * SAMPLE_MS / tickCount;
                String owner = entry.getKey();
                List<Map.Entry<String, long[]>> hot = main.topFrames(owner, 3);
                StringBuilder where = new StringBuilder();
                for (Map.Entry<String, long[]> frame : hot) {
                    if (!where.isEmpty()) where.append(", ");
                    where.append('`').append(frame.getKey()).append("` ")
                            .append(pct(frame.getValue()[0] / (double) Math.max(1, main.total)));
                }
                md.append("| ").append(owner).append(" | ").append(pct(share)).append(" | ").append(ms(perTick)).append(" | ")
                        .append(pct(perTick / TICK_BUDGET_MS)).append(" | ").append(where).append(" |\n");
                if (shown++ < 8) {
                    boolean server = owner.startsWith("Server");
                    report.chat.add(" " + (server ? "§7" : "§e") + owner + " §f" + ms(perTick) + " ms §7(" + pct(perTick / TICK_BUDGET_MS) + ")"
                            + (hot.isEmpty() || server ? "" : " §8– §7" + hot.getFirst().getKey()));
                }
            }
            md.append('\n');
            md.append("Zum Vergleich: Ø Tick-Zeit laut Paper ").append(ms(avg)).append(" ms. Liegt sie deutlich über der Summe der ")
                    .append("Stichproben im Tick, wartet der Haupt-Thread (Festplatte, Sperren, GC-Pausen) – das sieht keine Stichprobe.\n\n");
            if (main.total < 300) {
                md.append("_Wenige Stichproben: kurze Messung oder ein ruhiger Server, kleine Anteile sind ungenau._\n\n");
            }

            // spikes
            if (!spikeList.isEmpty()) {
                md.append("## Lag-Spitzen\n\nDie langsamsten Ticks (ab 50 ms), mit den Verursachern der Stichproben in dieser Zeit ")
                        .append("und GC-Pausen. Ohne Stichprobe hat der Haupt-Thread gewartet.\n\n");
                md.append("| Uhrzeit | Dauer | Verursacher (Stichproben à 10 ms) | GC-Pause |\n|---|---|---|---|\n");
                List<Spike> worst = new ArrayList<>(spikeList);
                worst.sort((a, b) -> Double.compare(b.ms(), a.ms()));
                for (Spike spike : first(worst, 15)) {
                    StringBuilder who = new StringBuilder();
                    for (Map.Entry<String, long[]> entry : first(sortedDesc(spike.owners()), 3)) {
                        if (!who.isEmpty()) who.append(", ");
                        who.append(entry.getKey()).append(" ").append(entry.getValue()[0]);
                    }
                    md.append("| ").append(CLOCK.format(spike.start())).append(" | ").append(ms(spike.ms())).append(" ms | ")
                            .append(who.isEmpty() ? "keine Stichprobe (Warten)" : who).append(" | ")
                            .append(spike.gcMs()[0] > 0 ? ms(spike.gcMs()[0]) + " ms" : "–").append(" |\n");
                }
                md.append('\n');
                Spike top = worst.getFirst();
                Map.Entry<String, long[]> culprit = top.owners().isEmpty() ? null : sortedDesc(top.owners()).getFirst();
                String why;
                if (top.gcMs()[0] >= top.ms() / 2) why = ", vor allem eine GC-Pause (" + ms(top.gcMs()[0]) + " ms)";
                else if (culprit != null) why = ", meist §f" + culprit.getKey() + " §7(" + culprit.getValue()[0] + " Stichproben)";
                else why = " §7(ohne Stichprobe: Warten)";
                report.chat.add("§7Schlimmste Spitze: §f" + ms(top.ms()) + " ms §7um " + CLOCK.format(top.start()) + why);
            }

            // other threads
            md.append("## Andere Threads (nicht im Tick-Budget)\n\n");
            md.append("Arbeit neben dem Haupt-Thread: Datenbank, Chunks, Netzwerk, asynchrone Aufgaben. Stichproben à 10 ms; "
                    + "Java erfasst pro Takt nur einige Threads, die Werte sind deshalb Untergrenzen.\n\n");
            md.append("| Verursacher | Stichproben | ≈ Kerne |\n|---|---|---|\n");
            for (Map.Entry<String, long[]> entry : first(others.ranked(), 12)) {
                md.append("| ").append(entry.getKey()).append(" | ").append(entry.getValue()[0]).append(" | ")
                        .append(String.format(Locale.GERMANY, "%.2f", entry.getValue()[0] * SAMPLE_MS / 1000.0 / wallSeconds)).append(" |\n");
            }
            md.append("\n| Thread | Stichproben |\n|---|---|\n");
            for (Map.Entry<String, long[]> entry : first(sortedDesc(threadGroups), 12)) {
                md.append("| ").append(entry.getKey()).append(" | ").append(entry.getValue()[0]).append(" |\n");
            }
            md.append('\n');

            // allocation
            if (allocationTotal > 0) {
                md.append("## Speicher-Umsatz nach Verursacher\n\nNeu angelegte Objekte (alle Threads), grob geschätzt aus Stichproben.\n\n");
                md.append("| Verursacher | Anteil | ≈ pro Sekunde |\n|---|---|---|\n");
                double scale = snap.allocatedBytes() > 0 ? snap.allocatedBytes() / (double) allocationTotal : 1.0;
                for (Map.Entry<String, long[]> entry : first(sortedDesc(allocation), 10)) {
                    md.append("| ").append(entry.getKey()).append(" | ").append(pct(entry.getValue()[0] / (double) allocationTotal))
                            .append(" | ").append(mb(entry.getValue()[0] * scale / wallSeconds)).append("/s |\n");
                }
                md.append('\n');
            }

            // hot spots per owner
            md.append("## Heiße Stellen je Verursacher (Haupt-Thread)\n\n");
            for (Map.Entry<String, long[]> entry : first(main.ranked(), 8)) {
                md.append("**").append(entry.getKey()).append("**\n\n");
                for (Map.Entry<String, long[]> frame : main.topFrames(entry.getKey(), 6)) {
                    md.append("- `").append(frame.getKey()).append("` ")
                            .append(pct(frame.getValue()[0] / (double) Math.max(1, main.total))).append('\n');
                }
                md.append('\n');
            }
            if (truncated > 0) {
                md.append("_").append(truncated).append(" Stichproben des Haupt-Threads hatten einen abgeschnittenen Stapel ")
                        .append("(sehr tiefe Aufrufe); sie zählen für den Teil, der noch zu sehen war._\n\n");
            }
        }

        // worlds
        md.append("## Welten am Ende der Messung\n\n| Welt | Spieler | Chunks | Entities | Block-Entities (tickend) |\n|---|---|---|---|---|\n");
        for (PerfSession.WorldInfo world : snap.worlds()) {
            md.append("| ").append(world.name()).append(" | ").append(world.players()).append(" | ").append(world.chunks())
                    .append(" | ").append(world.entities()).append(" | ").append(world.tileEntities()).append(" (")
                    .append(world.tickingTileEntities()).append(") |\n");
        }
        md.append("\n| Entity | Anzahl |\n|---|---|\n");
        List<Map.Entry<String, Integer>> types = new ArrayList<>(snap.entityTypes().entrySet());
        types.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Integer> type : first(types, 15)) {
            md.append("| ").append(type.getKey()).append(" | ").append(type.getValue()).append(" |\n");
        }
        int chunks = snap.worlds().stream().mapToInt(PerfSession.WorldInfo::chunks).sum();
        int entities = snap.worlds().stream().mapToInt(PerfSession.WorldInfo::entities).sum();
        int ticking = snap.worlds().stream().mapToInt(PerfSession.WorldInfo::tickingTileEntities).sum();
        report.chat.add("§7Welten: §f" + chunks + " §7Chunks, §f" + entities + " §7Entities, §f" + ticking + " §7tickende Block-Entities");

        md.append("\n## Hinweise\n\n");
        md.append("- Die Aufschlüsselung ist eine Stichprobe (alle 10 ms). Anteile unter etwa 1 % sind grob.\n");
        md.append("- Plugins werden über ihren Classloader erkannt. Code aus Bibliotheken, die ein Plugin über `libraries:` nachlädt, ")
                .append("zählt beim Server.\n");
        md.append("- „Messung selbst“ ist der Aufwand dieser Messung; die Aufnahme kostet zusätzlich etwa 1–2 % CPU.\n");
        if (session.keepRecording && recorded && !keepRefused && jfr != null) {
            md.append("- Die Aufnahme liegt als `").append(jfr.getFileName()).append("` daneben (öffnen mit JDK Mission Control).\n");
        }
        if (keepRefused) {
            md.append("- Die Aufnahme wurde nicht aufbewahrt: Gleichzeitig lief eine andere Java-Flight-Recorder-Aufnahme, deren ")
                    .append("Daten (auch Umgebungsvariablen) in derselben Datei gelandet wären.\n");
        }

        Path file = PerfSession.FOLDER.resolve("bericht-" + when + ".md");
        Files.writeString(file, md.toString(), StandardCharsets.UTF_8);
        report.chat.add("§7Ganzer Bericht: §f" + file.toString().replace('\\', '/') + " §7(/leistung berichte)");
        return report;
    }

    private static @Nullable Spike spikeAt(List<Spike> spikes, Instant at) {
        int low = 0;
        int high = spikes.size() - 1;
        Spike found = null;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            Spike spike = spikes.get(mid);
            if (spike.start().isAfter(at)) {
                high = mid - 1;
            } else {
                found = spike;
                low = mid + 1;
            }
        }
        return found != null && !at.isAfter(found.end()) ? found : null;
    }

    private static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int index = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

}
