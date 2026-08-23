package mc506lw.lapisTargetDummy.damage;

import mc506lw.lapisTargetDummy.config.DummyConfig;
import mc506lw.lapisTargetDummy.dummy.DummyCategory;
import mc506lw.lapisTargetDummy.dummy.DummyKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Spawns and recycles the floating damage numbers.
 * <p>
 * Three deliberate choices keep this cheap:
 * <ol>
 *   <li><b>Almost no per-tick movement.</b> The animation is expressed as
 *       interpolated {@link Transformation} changes — rise for the first part of
 *       the lifetime, then a shrink-and-fade for the last part. Each number costs
 *       three packets in total, not one per tick.</li>
 *   <li><b>Merging instead of stacking.</b> Repeated hits from the same attacker
 *       on the same dummy within a short window edit the existing display's text
 *       rather than spawning another entity, which is what keeps fast weapons
 *       from flooding the area with entities.</li>
 *   <li><b>A single self-cancelling sweeper.</b> Expiries live in a FIFO queue
 *       ordered by construction, so the sweeper only inspects the head. When the
 *       queue drains the task cancels itself, leaving no repeating task running
 *       while nobody is training.</li>
 * </ol>
 */
public final class DamageNumberRenderer {

    /** Sweeper interval. Two ticks is imperceptible and halves the wakeups. */
    private static final long SWEEP_PERIOD_TICKS = 2L;

    /** Length of a budget window. */
    private static final int BUDGET_WINDOW_TICKS = 20;

    /** Ticks of inactivity before a DPS summary is shown (default 20 = 1s). */
    private static final int DPS_IDLE_TICKS = 20;

    /** How many spiral positions a new number tries before settling. */
    private static final int SPOT_CANDIDATES = 8;

    /** Desired clearance between two numbers, in blocks. */
    private static final double SPOT_SPACING = 0.45D;

    /** Fraction of lifetime after which text starts fading (opacity ramp). */
    private static final double FADE_START_FRACTION = 0.7D;

    /** Minimum text opacity (0-255). 140 ≈ 55% — readable at night, still fades. */
    private static final byte MIN_TEXT_OPACITY = (byte) 140;

    /** Semi-transparent dark plate behind the digits, readable on any background. */
    private static final int BACKGROUND_ARGB = 0x64000000;

    /** DPS summary text color. */
    private static final TextColor DPS_COLOR = NamedTextColor.AQUA;

    private final Plugin plugin;
    private final DummyKeys keys;
    private DummyConfig config;

    /** Numbers awaiting removal, ordered by expiry because lifetime is uniform. */
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    /** Per-dummy spawn budget, refreshed once a second. */
    private final Map<UUID, Budget> budgets = new HashMap<>();
    /** DPS accumulators per (dummy, attacker). */
    private final Map<MergeKey, DpsAccumulator> dpsAccumulators = new HashMap<>();
    /** Pending DPS summary tasks per (dummy, attacker). */
    private final Map<MergeKey, BukkitTask> dpsTasks = new HashMap<>();

    private BukkitTask sweeper;

    public DamageNumberRenderer(Plugin plugin, DummyKeys keys, DummyConfig config) {
        this.plugin = plugin;
        this.keys = keys;
        this.config = config;
    }

    public void applyConfig(DummyConfig config) {
        this.config = config;
    }

    /**
     * Shows a damage number for a single hit. No merging — every hit gets its own
     * animated display. Simultaneously accumulates damage for a DPS summary that
     * appears after a short idle period.
     */
    public void show(ArmorStand dummy, UUID attackerId, DamageResult result) {
        int now = Bukkit.getCurrentTick();
        MergeKey key = new MergeKey(dummy.getUniqueId(), attackerId);

        // Budget check first.
        if (!claimBudget(dummy.getUniqueId(), now)) {
            return;
        }

        // Spawn individual hit number with full animation.
        int expiresAt = now + config.numberLifetimeTicks;
        Active entry = new Active(result, now, expiresAt);
        TextDisplay display = spawn(dummy, render(entry));
        if (display == null) {
            budgets.remove(dummy.getUniqueId());
            return;
        }
        entry.display = display;

        pending.addLast(new Pending(display, key, expiresAt));
        ensureSweeper();

        // Update DPS accumulator and (re)schedule the idle-end task.
        DpsAccumulator acc = dpsAccumulators.computeIfAbsent(key, k -> new DpsAccumulator());
        acc.add(result.finalized(), now, result.category());

        BukkitTask oldTask = dpsTasks.get(key);
        if (oldTask != null) {
            oldTask.cancel();
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            dpsTasks.remove(key);
            DpsAccumulator finished = dpsAccumulators.remove(key);
            if (finished != null && finished.hits > 0) {
                spawnDpsSummary(dummy, attackerId, finished);
            }
        }, DPS_IDLE_TICKS);
        dpsTasks.put(key, task);
    }

    /** Spawns a single "DPS: X.X" display with a distinct style. */
    private void spawnDpsSummary(ArmorStand dummy, UUID attackerId, DpsAccumulator acc) {
        double total = acc.totalDamage;
        double dps = total / (acc.durationTicks() / 20.0D);
        Component text = Component.text("DPS " + format(dps), DPS_COLOR);
        if (config.showCategoryMark && !acc.category.mark().isEmpty()) {
            text = text.append(Component.text(" " + acc.category.mark(), acc.category.color()));
        }

        int expiresAt = Bukkit.getCurrentTick() + config.numberLifetimeTicks;
        TextDisplay display = spawn(dummy, text);
        if (display != null) {
            pending.addLast(new Pending(display, new MergeKey(dummy.getUniqueId(), attackerId), expiresAt));
            ensureSweeper();
        }
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

        scheduleAnimation(display);
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

        // Collect live display locations once; N is tiny (budget caps at
        // max-per-second), so an O(candidates x live) scan is nothing.
        List<Location> live = new ArrayList<>(pending.size());
        for (Pending p : pending) {
            if (p.display.isValid()) {
                live.add(p.display.getLocation());
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
     * numbers stay readable at night. Two deferred writes = two packets.
     */
    private void scheduleAnimation(TextDisplay display) {
        final int lifetime = config.numberLifetimeTicks;
        final float rise = (float) config.numberRiseHeight;
        final float scale = config.numberScale;

        // 1) Rise interpolation: one deferred write, client interpolates
        //    from (y=0) to (y=rise) over the full lifetime.
        if (rise > 0.0F) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!display.isValid()) {
                    return;
                }
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(lifetime);
                display.setTransformation(transformation(rise, scale));
            }, 1L);
        }

        // 2) Text fade: starts at 70% of lifetime, linearly ramps opacity to
        //    MIN_TEXT_OPACITY (55%). No transformation change = no visual "pop".
        if (config.fadeOut) {
            final long fadeStart = Math.max(1L, (long) (lifetime * FADE_START_FRACTION));
            final int fadeSteps = Math.max(1, lifetime - (int) fadeStart);
            // We'll do it in 3 sub-steps to keep it smooth but packet-cheap.
            final int stepInterval = Math.max(1, fadeSteps / 3);
            for (int i = 1; i <= 3; i++) {
                final long delay = fadeStart + (long) i * stepInterval;
                final float progress = (float) i / 3.0F; // 0.33, 0.66, 1.0
                final byte opacity = (byte) (255 - (255 - MIN_TEXT_OPACITY) * progress);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (display.isValid()) {
                        display.setTextOpacity(opacity);
                    }
                }, delay);
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

    /**
     * Renders the accumulated state of one display. Every flag is taken from the
     * accumulation rather than from the newest hit, so a combo does not make the
     * crit star and the original-damage suffix flicker on and off.
     */
    private Component render(Active entry) {
        Component component = Component.text(format(entry.finalized), colorFor(entry.finalized));

        if (config.showOriginalDamage && entry.raw - entry.finalized > 0.01D) {
            component = component.append(
                    Component.text(" (" + format(entry.raw) + ")", NamedTextColor.DARK_GRAY));
        }
        if (config.showCategoryMark && !entry.category.mark().isEmpty()) {
            component = component.append(Component.text(" " + entry.category.mark(), entry.category.color()));
        }
        if (entry.critical) {
            component = component.append(Component.text(" \u2726", NamedTextColor.GOLD));
        }
        if (entry.hits > 1) {
            component = component.append(Component.text(" x" + entry.hits, NamedTextColor.GRAY));
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

    private boolean claimBudget(UUID dummyId, int now) {
        Budget budget = budgets.get(dummyId);
        if (budget == null || now - budget.windowStart >= BUDGET_WINDOW_TICKS) {
            budgets.put(dummyId, new Budget(now, 1));
            return true;
        }
        if (budget.used >= config.maxNumbersPerDummyPerSecond) {
            return false;
        }
        budget.used++;
        return true;
    }

    private void ensureSweeper() {
        if (sweeper != null) {
            return;
        }
        sweeper = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, SWEEP_PERIOD_TICKS, SWEEP_PERIOD_TICKS);
    }

    private void sweep() {
        int now = Bukkit.getCurrentTick();
        while (!pending.isEmpty() && pending.peekFirst().expiresAt <= now) {
            Pending expired = pending.pollFirst();
            if (expired.display.isValid()) {
                expired.display.remove();
            }
        }
        if (pending.isEmpty()) {
            budgets.clear();
            dpsAccumulators.clear();
            for (BukkitTask task : dpsTasks.values()) {
                task.cancel();
            }
            dpsTasks.clear();
            stopSweeper();
            return;
        }
        // Budgets are dropped as their windows close.
        Iterator<Budget> stale = budgets.values().iterator();
        while (stale.hasNext()) {
            if (now - stale.next().windowStart >= BUDGET_WINDOW_TICKS) {
                stale.remove();
            }
        }
    }

    private void stopSweeper() {
        if (sweeper != null) {
            sweeper.cancel();
            sweeper = null;
        }
    }

    /** Removes every live number. Called on disable and on reload. */
    public void shutdown() {
        stopSweeper();
        for (Pending entry : pending) {
            if (entry.display.isValid()) {
                entry.display.remove();
            }
        }
        pending.clear();
        budgets.clear();
        dpsAccumulators.clear();
        for (BukkitTask task : dpsTasks.values()) {
            task.cancel();
        }
        dpsTasks.clear();
    }

    /**
     * Drops the tracking state and stops the sweeper without deleting the live
     * displays. They are non-persistent, so the server discards them on unload.
     */
    public void stopTasks() {
        stopSweeper();
        pending.clear();
        budgets.clear();
        dpsAccumulators.clear();
        for (BukkitTask task : dpsTasks.values()) {
            task.cancel();
        }
        dpsTasks.clear();
    }

    private record MergeKey(UUID dummyId, UUID attackerId) {
    }

    private record Pending(TextDisplay display, MergeKey key, int expiresAt) {
    }

    /** Accumulated state of one on-screen number. */
    private static final class Active {
        private final int expiresAt;
        private TextDisplay display;
        private DummyCategory category;
        private double raw;
        private double finalized;
        private boolean critical;
        private int hits;
        private int lastHitTick;

        private Active(DamageResult result, int now, int expiresAt) {
            this.expiresAt = expiresAt;
            this.category = result.category();
            this.raw = result.raw();
            this.finalized = result.finalized();
            this.critical = result.critical();
            this.hits = 1;
            this.lastHitTick = now;
        }

        /**
         * Folds another hit in. The crit flag is sticky and the category follows
         * the latest hit, since the marker item is what defines it and it can
         * legitimately change mid-combo.
         */
        private void add(DamageResult result, int now) {
            this.raw += result.raw();
            this.finalized += result.finalized();
            this.critical |= result.critical();
            this.category = result.category();
            this.hits++;
            this.lastHitTick = now;
        }
    }

    private static final class Budget {
        private final int windowStart;
        private int used;

        private Budget(int windowStart, int used) {
            this.windowStart = windowStart;
            this.used = used;
        }
    }

    /** Accumulates damage for a DPS summary. */
    private static final class DpsAccumulator {
        private double totalDamage = 0.0;
        private int firstHitTick = -1;
        private int lastHitTick = -1;
        private int hits = 0;
        private DummyCategory category = DummyCategory.NONE;

        void add(double damage, int now, DummyCategory category) {
            if (firstHitTick == -1) {
                firstHitTick = now;
            }
            lastHitTick = now;
            totalDamage += damage;
            hits++;
            this.category = category;
        }

        int durationTicks() {
            if (firstHitTick == -1 || lastHitTick == firstHitTick) {
                return 1; // avoid div/0
            }
            return lastHitTick - firstHitTick;
        }
    }
}
