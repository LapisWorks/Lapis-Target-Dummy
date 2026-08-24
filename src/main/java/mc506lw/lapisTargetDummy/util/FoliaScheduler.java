package mc506lw.lapisTargetDummy.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduling helpers that work identically on Paper and Folia.
 * <p>
 * Folia has no global main thread: {@code Bukkit.getScheduler()} throws, and
 * work must be scheduled on the region thread that owns the affected entity.
 * Every deferred task in this plugin touches exactly one entity, so all of them
 * go through the entity's own scheduler, which runs its task in the right
 * region. On Paper the entity scheduler delegates to the main thread, so this
 * is a no-op wrapper there.
 * <p>
 * Retired entities (invalid or removed) have no scheduler; tasks for them are
 * dropped silently, which is always the correct outcome here — every delayed
 * body re-checks validity anyway.
 */
public final class FoliaScheduler {

    private static final AtomicBoolean FOLIA_DETECTED = new AtomicBoolean(false);
    private static volatile boolean foliaChecked = false;

    private FoliaScheduler() {
    }

    /**
     * Runs {@code task} for {@code entity} after {@code delayTicks}, on the
     * region thread that owns it (or the main thread on Paper).
     *
     * @return {@code true} when the task was scheduled, {@code false} when the
     *         entity was already gone and the task was dropped
     */
    public static boolean runAtEntity(Plugin plugin, Entity entity, long delayTicks, Runnable task) {
        if (!entity.isValid()) {
            return false;
        }
        try {
            entity.getScheduler().runDelayed(plugin, t -> task.run(), null, Math.max(1L, delayTicks));
            return true;
        } catch (IllegalStateException retired) {
            // The entity's scheduler is retired — it left its region between the
            // validity check and now. Dropping is correct: the caller's body only
            // ever animates or removes this same entity.
            return false;
        }
    }

    /**
     * @return true when running under Folia. Detected once by checking whether
     *         the global scheduler throws; used only for log wording.
     */
    public static boolean isFolia() {
        if (!foliaChecked) {
            synchronized (FoliaScheduler.class) {
                if (!foliaChecked) {
                    try {
                        Bukkit.getScheduler();
                    } catch (UnsupportedOperationException | IllegalStateException unsupported) {
                        FOLIA_DETECTED.set(true);
                    }
                    foliaChecked = true;
                }
            }
        }
        return FOLIA_DETECTED.get();
    }
}