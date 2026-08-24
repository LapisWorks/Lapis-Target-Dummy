package mc506lw.lapisTargetDummy.listener;

import mc506lw.lapisTargetDummy.config.DummyConfig;
import mc506lw.lapisTargetDummy.damage.AttackStrengthTracker;
import mc506lw.lapisTargetDummy.damage.DamageEmulator;
import mc506lw.lapisTargetDummy.damage.DamageNumberRenderer;
import mc506lw.lapisTargetDummy.damage.DamageResult;
import mc506lw.lapisTargetDummy.dummy.DummyCategory;
import mc506lw.lapisTargetDummy.dummy.DummyService;
import org.bukkit.EntityEffect;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns hits on a dummy into a damage read-out.
 * <p>
 * The event is always cancelled: the dummy takes no real damage, so it never
 * loses health, never gets knocked back, never drops its gear and never wears
 * down the attacker's weapon. That also removes any need to track or restore
 * dummy health.
 */
public final class DamageListener implements Listener {

    private final DummyService dummies;
    private final DamageEmulator emulator;
    private final DamageNumberRenderer renderer;
    private final AttackStrengthTracker strengthTracker;
    private DummyConfig config;

    /**
     * Per-dummy hurt-immunity state, mirroring vanilla's invulnerability window.
     * The dummy takes no real damage, so the server never starts its own
     * invulnerability timer — cancelled damage does not count as "hurt" — which
     * is why spam-clicking would otherwise produce a number per click. Here a hit
     * inside the window is ignored unless it deals more than the last recorded
     * one; then only the difference is shown, exactly like vanilla.
     * <p>
     * Concurrent because hits can arrive from different region threads at once
     * under Folia; each dummy's entry is only ever written from its own region.
     */
    private final Map<UUID, HurtState> hurtStates = new ConcurrentHashMap<>();

    public DamageListener(DummyService dummies,
                          DamageEmulator emulator,
                          DamageNumberRenderer renderer,
                          AttackStrengthTracker strengthTracker,
                          DummyConfig config) {
        this.dummies = dummies;
        this.emulator = emulator;
        this.renderer = renderer;
        this.strengthTracker = strengthTracker;
        this.config = config;
    }

    public void applyConfig(DummyConfig config) {
        this.config = config;
    }

    /**
     * Captures the attack-strength scale before the swing resets the cooldown.
     * Reading it in the damage handler would always yield a reset value.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPreAttack(io.papermc.paper.event.player.PrePlayerAttackEntityEvent event) {
        if (event.willAttack() && dummies.isDummy(event.getAttacked())) {
            strengthTracker.record(event.getPlayer());
        }
    }

    /**
     * Runs at {@code HIGHEST} so other plugins have already settled the base
     * damage, but before {@code MONITOR} observers see the cancellation.
     * <p>
     * {@code ignoreCancelled} is deliberately off. Region-protection plugins
     * routinely cancel damage to armor stands, and honouring that would silently
     * disable the read-out on exactly the kind of server that has a training area.
     * The dismantle path is the one cancellation that must win, so it is checked
     * explicitly below.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof ArmorStand stand) || !dummies.isDummy(stand)) {
            return;
        }

        // Administrative removal must stay possible: /kill is routed through a
        // cancellable KILL damage event, and VOID catches a dummy pushed out of
        // the world. Letting these through keeps the usual cleanup tools working.
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.KILL
                || cause == EntityDamageEvent.DamageCause.VOID
                || cause == EntityDamageEvent.DamageCause.CUSTOM) {
            return;
        }

        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            // Environmental damage: fire, cacti, lava, explosions. The dummy is
            // indestructible either way, so the only question is whether the hit
            // is reported. Off by default, because a dummy standing in a campfire
            // would otherwise spam numbers forever.
            if (config.showEnvironmentalDamage) {
                DamageResult ambient = emulator.simulate(stand, event, dummies.categoryOf(stand));
                renderer.show(stand, null, ambient);
            }
            event.setCancelled(true);
            return;
        }

        // The dismantle handler at LOW cancels the event it acts on. Producing a
        // read-out for that hit would be noise, and the stand is about to go away.
        if (event.isCancelled() && ProtectionListener.isDismantleAttack(byEntity, stand, dummies)) {
            return;
        }

        DummyCategory category = dummies.categoryOf(stand);
        DamageResult result = emulator.simulate(stand, event, category);

        Player attacker = resolveAttacker(byEntity.getDamager());
        UUID attackerId = attacker != null ? attacker.getUniqueId() : null;

        // Vanilla-style hurt immunity. The server never starts its own window
        // because every hit is cancelled, so one is simulated here: inside the
        // window a weaker-or-equal hit is swallowed whole, and a stronger one
        // shows only the part above the recorded hit — exactly how invulnerability
        // ticks behave for a real mob. The stored baseline is always the FULL
        // damage of the newest accepted hit, never the shown difference.
        //
        // The window is wall-clock (nanoseconds), not server ticks: Folia has no
        // global tick counter, and a per-dummy timer is correct on both platforms.
        long now = System.nanoTime();
        long windowNanos = config.hurtImmunityTicks * 50_000_000L;
        HurtState state = hurtStates.get(stand.getUniqueId());
        boolean immune = windowNanos > 0
                && state != null
                && now - state.lastHurtTick() < windowNanos;
        if (immune) {
            double excess = result.finalized() - state.lastDamage();
            if (excess <= 0.01D) {
                event.setCancelled(true);
                return;
            }
            double ratio = result.finalized() > 0.01D ? excess / result.finalized() : 0.0D;
            DamageResult shown = new DamageResult(result.raw() * ratio, excess,
                    result.category(), result.critical());
            renderer.show(stand, attackerId, shown);
        } else {
            renderer.show(stand, attackerId, result);
        }
        hurtStates.put(stand.getUniqueId(), new HurtState(now, result.finalized(), result.raw()));

        if (config.playHitSound) {
            stand.getWorld().playSound(stand.getLocation(),
                    result.critical() ? Sound.ENTITY_PLAYER_ATTACK_CRIT : Sound.ENTITY_ARMOR_STAND_HIT,
                    SoundCategory.PLAYERS, 0.7F, result.critical() ? 1.2F : 1.0F);
        }
        // Visual feedback without actual damage.
        stand.playEffect(EntityEffect.ARMOR_STAND_HIT);

        event.setCancelled(true);
    }

    /** Follows projectiles back to the shooting player so merging works. */
    private static Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        strengthTracker.forget(event.getPlayer().getUniqueId());
    }

    /** Last hit recorded for a dummy, for the invulnerability window. */
    private record HurtState(long lastHurtTick, double lastDamage, double lastRaw) {
    }
}
