package mc506lw.lapisTargetDummy.damage;

import mc506lw.lapisTargetDummy.config.DummyConfig;
import mc506lw.lapisTargetDummy.dummy.DummyCategory;
import mc506lw.lapisTargetDummy.dummy.DummyKeys;
import mc506lw.lapisTargetDummy.util.FoliaScheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spawns and recycles the floating damage numbers.
 * <p>
 * Three deliberate choices keep this cheap and region-thread-safe:
 * <ol>
 *   <li><b>Almost no per-tick movement.</b> The animation is expressed as
 *       interpolated {@link Transformation} changes — rise for most of the
 *       lifetime, then a text-opacity fade. Each number costs a handful of
 *       packets, not one per tick.</li>
 *   <li><b>No global sweeper.</b> A repeating global task cannot exist on Folia,
 *       which has no main thread. Instead each number schedules its own removal
 *       on its own entity scheduler — the task runs on the region thread that
 *       owns the display, wherever that may be. On Paper this behaves exactly
 *       like a delayed main-thread task.</li>
 *   <li><b>Merging instead of stacking for the DPS summary.</b> Individual hits
 *       each get their own number (with position avoidance), while per-attacker
 *       damage is accumulated in a concurrent map and summarized once combat
 *       goes quiet.</li>
 * </ol>
 * All shared state is {@link ConcurrentHashMap}-based because hits can arrive
 * from different region threads simultaneously under Folia; every value is
 * either immutable or confined to one dummy's region.
 */
public final class DamageNumberRenderer {

    /** Length of a budget window, in nanoseconds (one second). */
    private static final long BUDGET_WINDOW_NANOS = 1_000_000_000L;

    /** Ticks of inactivity before a DPS summary is shown. */
    private static final int DPS_IDLE_TICKS = 20;

    /** Fraction of lifetime after which text starts fading (opacity ramp). */
    private static final double FADE_START_FRACTION = 0.7D;

    /** Minimum text opacity (0-255). 140 ≈ 55% — readable at night, still fades. */
    private static final byte MIN_TEXT_OPACITY = (byte) 140;

    /** Semi-transparent dark plate behind the digits, readable on any background. */
    private static final int BACKGROUND_ARGB = 0x64000000;

    /** How many spiral positions a new number tries before settling. */
    private static final int SPOT_CANDIDATES = 8;

    /** Desired clearance between two numbers, in blocks. */
    private static final double SPOT_SPACING = 0.45D;

    /** DPS summary text color. */
    private static final TextColor DPS_COLOR = NamedTextColor.AQUA;

    private final Plugin plugin;
    private final DummyKeys keys;
    private volatile DummyConfig config;

    /** Live numbers keyed by their display's UUID — written by many regions. */
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    /** Per-dummy spawn budget, refreshed once a second. */
    private final Map<UUID, Budget> budgets = new ConcurrentHashMap<>();
    /** DPS accumulators per (dummy, attacker). */
    private final Map<MergeKey, DpsAccumulator> dpsAccumulators = new ConcurrentHashMap<>();
    /** Guards exactly-one DPS summary per key when the idle timer lapses. */
    private final Map<MergeKey, AtomicBoolean> dpsClosing = new ConcurrentHashMap<>();

    public DamageNumberRenderer(Plugin plugin, DummyKeys keys, DummyConfig config) {
        this.plugin = plugin;
        this.keys = keys;
        this.config = config;
    }

    public void applyConfig(DummyConfig config) {
        this.config = config;
    }

    /**
     * Shows a damage number for a single hit. Every hit gets its own animated
     * display. Simultaneously accumulates damage for a DPS summary that appears
     * after a short idle period.
     */
    public void show(ArmorStand dummy, UUID attackerId, DamageResult result) {
        long now = System.nanoTime();
        MergeKey key = new MergeKey(dummy.getUniqueId(), attackerId);

        if (!claimBudget(dummy.getUniqueId(), now)) {
            return;
        }

        TextDisplay display = spawn(dummy, render(result));
        if (display == null) {
            budgets.remove(dummy.getUniqueId());
            return;
        }

        trackExpiry(display);

        DpsAccumulator acc = dpsAccumulators.computeIfAbsent(key, k -> new DpsAccumulator());
        synchronized (acc) {
            acc.add(result.finalized(), result.category());
        }
        dpsClosing.computeIfAbsent(key, k -> new AtomicBoolean(false));

        // The idle timer restarts on every hit. When it finally lapses, an
        // AtomicBoolean makes sure exactly one of any racing firings emits the
        // summary and clears the accumulator.
        FoliaScheduler.runAtEntity(plugin, dummy, DPS_IDLE_TICKS, () -> {
            AtomicBoolean closing = dpsClosing.get(key);
            if (closing == null || !closing.compareAndSet(false, true)) {
                return;
            }
            DpsAccumulator finished = dpsAccumulators.remove(key);
            dpsClosing.remove(key);
            if (finished != null && finished.hits > 0) {
                spawnDpsSummary(dummy, attackerId, finished);
            }
        });
    }

    /** Spawns a single "DPS X.X" display with a distinct style. */
    private void spawnDpsSummary(ArmorStand dummy, UUID attackerId, DpsAccumulator acc) {
        double dps;
        DummyCategory category;
        synchronized (acc) {
            dps = acc.totalDamage / Math.max(1, acc.durationTicks / 20);
            category = acc.category;
        }
        Component text = Component.text("DPS " + format(dps), DPS_COLOR);
        if (config.showCategoryMark && !category.mark().isEmpty()) {
            text = text.append(Component.text(" " + category.mark(), category.color()));
        }

        TextDisplay display = spawn(dummy, text);
        if (display != null) {
            trackExpiry(display);
        }
    }

    /**
     * Schedules the number's own removal on its own scheduler: after the
     * configured lifetime the display is removed and its bookkeeping dropped.
     * This replaces the old global sweeper, which cannot exist on Folia.
     */
    private void trackExpiry(TextDisplay display) {
        pending.put(display.getUniqueId(), new Pending(display));
        FoliaScheduler.runAtEntity(plugin, display, config.numberLifetimeTicks, () -> {
            pending.remove(display.getUniqueId());
            if (display.isValid()) {
                display.remove();
            }
        });
    }

    private TextDisplay spawn(ArmorStand dummy, Component text) {
        World world = dummy.getWorld();
        Location at = findFreeSpot(dummy);
        at.setYaw(0.0F);
        at.setPitch(0.0F);

        TextDisplay display;
        try {
            display = world.spawn(at, TextDisplay.class, spawned -> {
                spawned.text(text);
                spawned.setBillboard(Display.Billboard.CENTER);
                spawned.setAlignment(TextDisplay.TextAlignment.CENTER);
                spawned.setSeeThrough(false);
                // A dark plate behind the digits keeps them readable against both
                // a white desert sky and a dark cave; the alpha leaves the world
                // visible through it.
                spawned.setBackgroundColor(Color.fromARGB(BACKGROUND_ARGB));
                spawned.setShadowed(true);
                spawned.setViewRange(config.numberViewRange);
                spawned.setPersistent(false);
                spawned.setGravity(false);
                spawned.setInvulnerable(true);
                spawned.setSilent(true);
                spawned.setTransformation(transformation(0.0F, config.numberScale));
                spawned.getPersistentDataContainer().set(keys.number, PersistentDataType.BYTE, (byte) 1);
            });
        } catch (IllegalArgumentException | IllegalStateException e) {
            return null;
        }

        scheduleAnimation(dummy, display);
        return display;
    }

    /**
     * Picks a spawn position that does not overlap a still-living number.
     * <p>
     * Candidate offsets walk a sunflower (golden-angle) spiral around the dummy's
     * head: the first candidate is the plain default spot, and every further one
     * is roughly {@link #SPOT_SPACING} blocks away from all live displays. The
     * spiral keeps positions deterministic-looking and evenly distributed instead
     * of the clumps pure randomness produces.
     */
    private Location findFreeSpot(ArmorStand dummy) {
        Location base = dummy.getLocation();
        base.setY(base.getY() + config.numberSpawnHeight);
        double spread = config.numberSpreadRadius;

        List<Location> live = new ArrayList<>(pending.size());
        for (Pending p : pending.values()) {
            TextDisplay display = p.display();
            if (display.isValid()) {
                live.add(display.getLocation());
            }
        }
        if (live.isEmpty() || spread <= 0.0D) {
            return base;
        }

        final double minDistSq = Math.pow(SPOT_SPACING + spread, 2);
        Location best = null;
        double bestScore = -1.0D;
        for (int i = 0; i < SPOT_CANDIDATES; i++) {
            // Golden angle in radians ≈ 2.39996; r grows with sqrt(i), which is
            // what makes the sunflower pattern evenly packed.
            double angle = i * 2.39996D;
            double radius = spread * Math.sqrt(i);
            Location candidate = base.clone().add(Math.cos(angle) * radius, 0.0D, Math.sin(angle) * radius);

            double nearestSq = Double.MAX_VALUE;
            for (Location other : live) {
                double dx = candidate.getX() - other.getX();
                double dy = candidate.getY() - other.getY();
                double dz = candidate.getZ() - other.getZ();
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq < nearestSq) {
                    nearestSq = distSq;
                }
            }
            if (nearestSq >= minDistSq) {
                return candidate;
            }
            if (nearestSq > bestScore) {
                bestScore = nearestSq;
                best = candidate;
            }
        }
        // Every candidate collides with something — take the least-crowded one.
        return best != null ? best : base;
    }

    /**
     * Single smooth rise for the whole lifetime, plus a text-opacity fade in the
     * last 30%. The background plate stays fully visible the whole time, so
     * numbers stay readable at night. Each write is deferred through the
     * display's own scheduler, which lands on the right region thread.
     */
    private void scheduleAnimation(ArmorStand dummy, TextDisplay display) {
        final int lifetime = config.numberLifetimeTicks;
        final float rise = (float) config.numberRiseHeight;
        final float scale = config.numberScale;

        // 1) Rise interpolation: one deferred write, client interpolates from
        //    (y=0) to (y=rise) over the full lifetime.
        if (rise > 0.0F) {
            FoliaScheduler.runAtEntity(plugin, display, 1L, () -> {
                if (!display.isValid()) {
                    return;
                }
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(lifetime);
                display.setTransformation(transformation(rise, scale));
            });
        }

        // 2) Text fade over the tail of the lifetime, in three steps to stay
        //    packet-cheap while still reading as a fade.
        if (config.fadeOut) {
            final long fadeStart = Math.max(1L, (long) (lifetime * FADE_START_FRACTION));
            final long stepInterval = Math.max(1L, (lifetime - fadeStart) / 3L);
            for (int i = 1; i <= 3; i++) {
                final long delay = fadeStart + i * stepInterval;
                final float progress = i / 3.0F;
                final byte opacity = (byte) (255 - (255 - MIN_TEXT_OPACITY) * progress);
                FoliaScheduler.runAtEntity(plugin, display, delay, () -> {
                    if (display.isValid()) {
                        display.setTextOpacity(opacity);
                    }
                });
            }
        }
    }

    private static Transformation transformation(float riseY, float scale) {
        return new Transformation(
                new Vector3f(0.0F, riseY, 0.0F),
                new AxisAngle4f(),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f());
    }

    /** Renders one hit: value, optional pre-mitigation suffix, marks, crit star. */
    private Component render(DamageResult result) {
        Component component = Component.text(format(result.finalized()), colorFor(result.finalized()));

        if (config.showOriginalDamage && result.raw() - result.finalized() > 0.01D) {
            component = component.append(
                    Component.text(" (" + format(result.raw()) + ")", NamedTextColor.DARK_GRAY));
        }
        if (config.showCategoryMark && !result.category().mark().isEmpty()) {
            component = component.append(Component.text(" " + result.category().mark(), result.category().color()));
        }
        if (result.critical()) {
            component = component.append(Component.text(" \u2726", NamedTextColor.GOLD));
        }
        return component;
    }

    private String format(double value) {
        return String.format(Locale.ROOT, config.numberFormat, value);
    }

    /** Warm-to-hot ramp so the damage magnitude is readable at a glance. */
    private static TextColor colorFor(double damage) {
        if (damage <= 0.05D) {
            return NamedTextColor.GRAY;
        }
        if (damage < 4.0D) {
            return NamedTextColor.WHITE;
        }
        if (damage < 8.0D) {
            return NamedTextColor.YELLOW;
        }
        if (damage < 14.0D) {
            return NamedTextColor.GOLD;
        }
        if (damage < 22.0D) {
            return NamedTextColor.RED;
        }
        return NamedTextColor.LIGHT_PURPLE;
    }

    private boolean claimBudget(UUID dummyId, long now) {
        Budget fresh = new Budget(now, 1);
        while (true) {
            Budget budget = budgets.get(dummyId);
            if (budget == null || now - budget.windowStart >= BUDGET_WINDOW_NANOS) {
                // putIfAbsent vs replace matters only for racing writers; either
                // way exactly one window wins and the hit counts once.
                if (budgets.put(dummyId, fresh) == budget || budget == null) {
                    return true;
                }
                continue;
            }
            if (budget.used >= config.maxNumbersPerDummyPerSecond) {
                return false;
            }
            if (budget.tryConsume()) {
                return true;
            }
            // Lost a race for the last slot; loop re-reads state.
        }
    }

    /** Removes every live number. Called on disable and on reload. */
    public void shutdown() {
        for (Pending entry : pending.values()) {
            TextDisplay display = entry.display();
            if (display.isValid()) {
                display.remove();
            }
        }
        clearState();
    }

    /**
     * Drops the tracking state without deleting the live displays. They are
     * non-persistent, so the server discards them on unload; their self-removal
     * tasks die with them.
     */
    public void stopTasks() {
        clearState();
    }

    private void clearState() {
        pending.clear();
        budgets.clear();
        dpsAccumulators.clear();
        dpsClosing.clear();
    }

    /** A live number awaiting expiry. */
    private record Pending(TextDisplay display) {
    }

    /** Per-dummy spawn budget; consumed atomically from any region thread. */
    private static final class Budget {
        private final long windowStart;
        private final AtomicBoolean lock = new AtomicBoolean();
        private int used;

        private Budget(long windowStart, int used) {
            this.windowStart = windowStart;
            this.used = used;
        }

        boolean tryConsume() {
            if (!lock.compareAndSet(false, true)) {
                return false;
            }
            try {
                if (used >= 200) { // sanity ceiling; config clamp is lower anyway
                    return false;
                }
                used++;
                return true;
            } finally {
                lock.set(false);
            }
        }
    }

    /** Accumulates damage for a DPS summary; confined to one dummy's region but synchronized anyway. */
    private static final class DpsAccumulator {
        private double totalDamage;
        private int durationTicks = 1;
        private int hits;
        private DummyCategory category = DummyCategory.NONE;

        void add(double damage, DummyCategory category) {
            totalDamage += damage;
            durationTicks += 1;
            hits++;
            this.category = category;
        }
    }

    private record MergeKey(UUID dummyId, UUID attackerId) {
    }
}