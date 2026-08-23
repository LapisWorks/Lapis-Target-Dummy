package mc506lw.lapisTargetDummy.damage;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Records each player's attack-strength scale immediately before their swing.
 * <p>
 * Vanilla scales the category enchantment bonus by the attack-strength scale,
 * but the swing resets the cooldown before the damage event fires, so reading
 * {@link Player#getAttackCooldown()} inside the damage handler would always
 * return a value close to zero. {@code PrePlayerAttackEntityEvent} fires before
 * the reset, so the value is captured there and consumed one tick later at
 * most.
 * <p>
 * The map only ever holds players who are actively hitting a dummy; entries are
 * dropped on quit and stale ones are ignored by tick stamp, so it cannot grow
 * without bound.
 */
public final class AttackStrengthTracker {

    /** A recorded scale older than this many ticks is treated as absent. */
    private static final int STALE_AFTER_TICKS = 4;

    private final Map<UUID, Sample> samples = new HashMap<>();

    public void record(Player player) {
        samples.put(player.getUniqueId(), new Sample(player.getAttackCooldown(), Bukkit.getCurrentTick()));
    }

    /**
     * @return the scale recorded just before the swing, or {@code 1.0} when no
     *         fresh sample exists (a full-strength hit, which is also what
     *         non-melee sources such as arrows should use)
     */
    public float scaleFor(Player player) {
        Sample sample = samples.get(player.getUniqueId());
        if (sample == null || Bukkit.getCurrentTick() - sample.tick > STALE_AFTER_TICKS) {
            return 1.0F;
        }
        return sample.scale;
    }

    public void forget(UUID playerId) {
        samples.remove(playerId);
    }

    public void clear() {
        samples.clear();
    }

    private record Sample(float scale, int tick) {
    }
}
