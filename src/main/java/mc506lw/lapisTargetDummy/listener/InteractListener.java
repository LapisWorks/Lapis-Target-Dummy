package mc506lw.lapisTargetDummy.listener;

import mc506lw.lapisTargetDummy.config.DummyConfig;
import mc506lw.lapisTargetDummy.dummy.DummyService;
import mc506lw.lapisTargetDummy.ui.DummyEquipmentMenu;
import mc506lw.lapisTargetDummy.util.Registries;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Right-click handling: plain right-click opens the equipment menu.
 * <p>
 * Both {@link PlayerInteractAtEntityEvent} and {@link PlayerInteractEntityEvent}
 * are handled, because which one arrives for an armor stand depends on how the
 * client resolved the interaction locally: when it decides the interaction
 * succeeded it never sends the follow-up {@code INTERACT} packet, so listening to
 * only one of the two silently misses right-clicks on some versions. A per-tick
 * guard keeps the double registration from acting twice on one click.
 */
public final class InteractListener implements Listener {

    private final DummyService dummies;
    private final Registries registries;
    private DummyConfig config;

    /** Last serve time per player, to collapse duplicate interact events. */
    private final java.util.Map<java.util.UUID, Long> lastHandledTick = new java.util.concurrent.ConcurrentHashMap<>();

    public InteractListener(DummyService dummies, Registries registries, DummyConfig config) {
        this.dummies = dummies;
        this.registries = registries;
        this.config = config;
    }

    public void applyConfig(DummyConfig config) {
        this.config = config;
    }

    /**
     * Vanilla armor-stand manipulation is suppressed on dummies so the slots stay
     * under the menu's control and marker items cannot be swapped out by accident.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onManipulate(PlayerArmorStandManipulateEvent event) {
        if (dummies.isDummy(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractAt(PlayerInteractAtEntityEvent event) {
        handle(event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        handle(event);
    }

    private void handle(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!(event.getRightClicked() instanceof ArmorStand stand) || !dummies.isDummy(stand)) {
            return;
        }

        Player player = event.getPlayer();
        event.setCancelled(true);
        if (!claimTick(player)) {
            return;
        }

        // A plain right-click opens the menu. Sneaking is not required: the
        // interact events for armor stands are unreliable enough that stacking
        // another condition on top makes opening feel like a coin flip. The
        // config keeps an opt-in stricter mode.
        if (config.requireSneakToOpenMenu && !player.isSneaking()) {
            return;
        }
        if (!player.hasPermission("lapistargetdummy.equip")) {
            return;
        }
        DummyEquipmentMenu.open(player, stand, registries);
    }

    /**
     * @return {@code true} for the first interact event a player produces in a
     *         tick, {@code false} for the duplicate that may follow. Uses
     *         wall-clock time: Folia has no global tick counter, and a 50 ms
     *         dedup window is equivalent for this purpose.
     */
    private boolean claimTick(Player player) {
        long now = System.nanoTime();
        Long previous = lastHandledTick.put(player.getUniqueId(), now);
        return previous == null || now - previous > 50_000_000L;
    }
}