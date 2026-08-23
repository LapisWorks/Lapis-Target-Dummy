package mc506lw.lapisTargetDummy.listener;

import mc506lw.lapisTargetDummy.dummy.DummyKeys;
import mc506lw.lapisTargetDummy.dummy.DummyService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.EnumSet;
import java.util.Set;

/**
 * Keeps dummies and their display entities intact, and provides the sneak-attack
 * dismantle path.
 */
public final class ProtectionListener implements Listener {

    /** Removals after which the dummy is expected back, so nothing is cleaned up. */
    @SuppressWarnings("removal")
    private static final Set<EntityRemoveEvent.Cause> TRANSIENT_REMOVALS = EnumSet.of(
            EntityRemoveEvent.Cause.UNLOAD,
            EntityRemoveEvent.Cause.TRANSFORMATION,
            EntityRemoveEvent.Cause.MERGE);

    private final Plugin plugin;
    private final DummyService dummies;
    private final DummyKeys keys;

    public ProtectionListener(Plugin plugin, DummyService dummies, DummyKeys keys) {
        this.plugin = plugin;
        this.dummies = dummies;
        this.keys = keys;
    }

    /**
     * Sneak-attacking with an empty hand dismantles the dummy.
     * <p>
     * The empty-hand requirement matters: players spend most of their time here
     * hitting the dummy with weapons, and sneaking to inspect the numbers is
     * natural, so a weapon-agnostic sneak-attack would destroy dummies by
     * accident. Runs at {@code LOW} and cancels the event;
     * {@link DamageListener} runs later at {@code HIGHEST} and skips a cancelled
     * dismantle attack explicitly, so the killing blow produces no read-out.
     * <p>
     * Like the read-out handler, prior cancellations are ignored here on purpose:
     * a region plugin that blocks armor-stand damage must not also block removal.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onDismantle(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ArmorStand stand) || !dummies.isDummy(stand)) {
            return;
        }
        if (!isDismantleAttack(event, stand, dummies)) {
            return;
        }
        Player player = (Player) event.getDamager();
        if (!player.hasPermission("lapistargetdummy.destroy")) {
            return;
        }
        event.setCancelled(true);
        // Deferred by a tick: removing the entity here would leave every later
        // listener in this event holding a reference to a dead armor stand.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (stand.isValid() && dummies.isDummy(stand)) {
                dummies.dismantle(stand);
            }
        });
        player.sendActionBar(Component.text("训练假人已拆除", NamedTextColor.YELLOW));
    }

    /**
     * Whether a hit is a dismantle attempt: a sneaking player with an empty main
     * hand. Shared with {@link DamageListener}, which must not print a damage
     * number for the hit that removes the dummy.
     */
    static boolean isDismantleAttack(EntityDamageByEntityEvent event, ArmorStand stand, DummyService dummies) {
        if (!dummies.isDummy(stand)) {
            return false;
        }
        return event.getDamager() instanceof Player player
                && player.isSneaking()
                && player.getInventory().getItemInMainHand().getType().isAir();
    }

    /**
     * The plugin's own damage number displays are never a legitimate damage target.
     * <p>
     * Dummy protection is not handled here: {@link DamageListener} cancels every
     * damage event on a dummy at {@code HIGHEST}, after it has read the numbers
     * out of it, so cancelling earlier here would only hide the hit from itself.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDisplayDamage(EntityDamageEvent event) {
        Entity victim = event.getEntity();
        if (!(victim instanceof TextDisplay)) {
            return;
        }
        // Scoped to this plugin's own entities: displays belonging to other
        // plugins or datapacks are none of our business.
        PersistentDataContainer data = victim.getPersistentDataContainer();
        if (data.has(keys.number, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }
}