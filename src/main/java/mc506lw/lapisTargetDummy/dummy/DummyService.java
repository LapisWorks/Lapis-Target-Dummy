package mc506lw.lapisTargetDummy.dummy;

import mc506lw.lapisTargetDummy.config.DummyConfig;
import mc506lw.lapisTargetDummy.util.Registries;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Creation, identification and teardown of dummies.
 * <p>
 * All state is stored in the armor stand's own persistent data container, so it
 * travels with the world save. The plugin keeps no registry and performs no
 * startup scan, which makes enable and reload constant-time regardless of how
 * many dummies exist.
 */
public final class DummyService {

    /** Bumped when the stored layout changes, so old dummies can be migrated. */
    private static final byte SCHEMA_VERSION = 1;

    /** The slots an armor stand actually has, in menu order. */
    public static final EquipmentSlot[] DUMMY_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
            EquipmentSlot.HAND, EquipmentSlot.OFF_HAND
    };

    private final DummyKeys keys;
    private final Registries registries;
    private DummyConfig config;

    public DummyService(DummyKeys keys, Registries registries, DummyConfig config) {
        this.keys = keys;
        this.registries = registries;
        this.config = config;
    }

    public void applyConfig(DummyConfig config) {
        this.config = config;
    }

    public boolean isDummy(Entity entity) {
        return entity instanceof ArmorStand
                && entity.getPersistentDataContainer().has(keys.dummy, PersistentDataType.BYTE);
    }

    /**
     * @return {@code true} when the block directly beneath {@code location} is
     *         the configured base block
     */
    public boolean isOnBaseBlock(Location location) {
        Location below = location.clone();
        below.setY(Math.floor(below.getY()) - 1.0D);
        return below.getBlock().getType() == config.baseBlock;
    }

    /** Turns a freshly placed armor stand into a dummy. */
    public void promote(ArmorStand stand) {
        stand.getPersistentDataContainer().set(keys.dummy, PersistentDataType.BYTE, SCHEMA_VERSION);
        stand.setArms(true);
        stand.setBasePlate(true);
        stand.setGravity(false);
        stand.setPersistent(true);
        stand.setInvulnerable(false);
        stand.setCanMove(false);
        stand.setCanTick(config.tickDummies);
        stand.customName(Component.text("训练假人", NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        stand.setCustomNameVisible(false);
        // The stand must not be manipulable by hand: equipment is edited through
        // the menu, which allows arbitrary items in every slot.
        stand.addDisabledSlots(DUMMY_SLOTS);
    }

    public DummyCategory categoryOf(ArmorStand dummy) {
        return categoryOf(dummy, registries);
    }

    /**
     * Static form, so the equipment menu can display exactly what the damage path
     * will use without holding a service reference.
     */
    public static DummyCategory categoryOf(ArmorStand dummy, Registries registries) {
        EntityEquipment equipment = dummy.getEquipment();
        if (equipment == null) {
            return DummyCategory.NONE;
        }
        return DummyCategory.fromMarker(equipment.getItemInMainHand(), registries);
    }

    /**
     * Removes a dummy, dropping the stand plus everything it was wearing.
     */
    public void dismantle(ArmorStand dummy) {
        Location at = dummy.getLocation();
        World world = at.getWorld();

        EntityEquipment equipment = dummy.getEquipment();
        if (equipment != null) {
            // Enumerated explicitly rather than over EquipmentSlot.values(),
            // which includes slots an armor stand does not have (BODY, SADDLE)
            // and would throw.
            for (EquipmentSlot slot : DUMMY_SLOTS) {
                ItemStack item = equipment.getItem(slot);
                if (item != null && !item.getType().isAir()) {
                    world.dropItemNaturally(at, item.clone());
                }
            }
        }
        world.dropItemNaturally(at, new ItemStack(Material.ARMOR_STAND));
        dummy.remove();
    }
}