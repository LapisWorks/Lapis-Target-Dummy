package mc506lw.lapisTargetDummy.damage;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records each player's attack-strength scale immediately before their swing.
 * <p>
 * Vanilla scales the category enchantment bonus by the attack-strength scale,
 * but the swing resets the cooldown before the damage event fires, so reading
 * {@link Player#getAttackCooldown()} inside the damage handler would always
 * return a value close to zero. {@code PrePlayerAttackEntityEvent} fires before
 * the reset, so the value is captured there and consumed moments later at most.
 * <p>
 * Freshness is measured with {@link System#nanoTime()} rather than server ticks:
 * on Folia there is no global tick counter, and a per-player wall-clock window
 * is just as correct here — a sample is only ever meant to pair one attack
 * packet with its immediately following damage event. The map only holds
 * players actively hitting dummies; entries are dropped on quit, and stale ones
 * are treated as absent.
 */
public final class AttackStrengthTracker {

    /** A recorded sample older than this is treated as absent. */
    private static final long STALE_AFTER_NANOS = 200_000_000L; // 0.2 s

    private final Map<UUID, Sample> samples = new ConcurrentHashMap<>();

    public void record(Player player) {
        samples.put(player.getUniqueId(), new Sample(player.getAttackCooldown(), System.nanoTime()));
    }

    /**
     * @return the scale recorded just before the swing, or {@code 1.0} when no
     *         fresh sample exists (a full-strength hit, which is also what
     *         non-melee sources such as arrows should use)
     */
    public float scaleFor(Player player) {
        Sample sample = samples.get(player.getUniqueId());
        if (sample == null || System.nanoTime() - sample.tick() > STALE_AFTER_NANOS) {
            return 1.0F;
        }
        return sample.scale();
    }

    public void forget(UUID playerId) {
        samples.remove(playerId);
    }

    public void clear() {
        samples.clear();
    }

    private record Sample(float scale, long tick) {
    }
}