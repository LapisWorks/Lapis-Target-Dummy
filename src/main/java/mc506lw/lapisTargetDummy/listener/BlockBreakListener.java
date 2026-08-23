package mc506lw.lapisTargetDummy.listener;

import mc506lw.lapisTargetDummy.config.DummyConfig;
import mc506lw.lapisTargetDummy.dummy.DummyService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * When the base block under a dummy is broken, drop the armor stand so the
 * player can recover it.
 */
public final class BlockBreakListener implements Listener {

    private final DummyService dummies;
    private final DummyConfig config;

    public BlockBreakListener(DummyService dummies, DummyConfig config) {
        this.dummies = dummies;
        this.config = config;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != config.baseBlock) {
            return;
        }
        // Check if there's a dummy standing on this block
        Location above = block.getLocation().add(0.5, 1.0, 0.5);
        for (Entity entity : block.getWorld().getNearbyEntities(above, 0.5, 1.5, 0.5)) {
            if (entity instanceof ArmorStand stand && dummies.isDummy(stand)) {
                dummies.dismantle(stand);
                break; // Only one dummy per block
            }
        }
    }
}