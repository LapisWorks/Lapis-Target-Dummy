package mc506lw.lapisTargetDummy.listener;

import mc506lw.lapisTargetDummy.dummy.DummyService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.plugin.Plugin;

/**
 * Converts an armor stand placed on the base block into a dummy.
 */
public final class PlacementListener implements Listener {

    private final Plugin plugin;
    private final DummyService dummies;

    public PlacementListener(Plugin plugin, DummyService dummies) {
        this.plugin = plugin;
        this.dummies = dummies;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ArmorStand stand)) {
            return;
        }
        // getBlock() is the clicked block, whose relationship to the stand
        // depends on the face used, so the stand's own footprint is checked.
        if (!dummies.isOnBaseBlock(stand.getLocation())) {
            return;
        }
        Player player = event.getPlayer();
        if (player != null && !player.hasPermission("lapistargetdummy.create")) {
            return;
        }
        // Deferred by one tick so the vanilla placement finishes writing the
        // stand's own state before it is reconfigured.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!stand.isValid() || dummies.isDummy(stand)) {
                return;
            }
            dummies.promote(stand);
            if (player != null && player.isOnline()) {
                player.sendActionBar(Component.text("训练假人已创建", NamedTextColor.AQUA));
            }
        });
    }
}
