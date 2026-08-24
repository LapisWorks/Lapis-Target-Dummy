package mc506lw.lapisTargetDummy.ui;

import mc506lw.lapisTargetDummy.dummy.DummyCategory;
import mc506lw.lapisTargetDummy.dummy.DummyService;
import mc506lw.lapisTargetDummy.util.Registries;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.UUID;

/**
 * A nine-slot chest menu that maps directly onto the dummy's equipment.
 * <p>
 * Going through a menu rather than vanilla armor-stand manipulation is what lets
 * any item be placed in any slot, including non-armor items in the armor slots.
 * <p>
 * The menu holds no state of its own: it is a rendering of the dummy's equipment,
 * repainted from the dummy after every click. That is what keeps two players
 * editing the same dummy from seeing — or taking — an item that is no longer
 * there. {@link #refreshAll} repaints every open view of one dummy, so a change
 * made by one viewer is immediately reflected for the others.
 * <p>
 * The instance itself is the {@link InventoryHolder}, so the click listener
 * identifies the menu by holder type instead of by title.
 */
public final class DummyEquipmentMenu implements InventoryHolder {

    /** Slot index to equipment slot; {@code null} entries are decoration. */
    private static final EquipmentSlot[] LAYOUT = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
            null,
            EquipmentSlot.HAND, EquipmentSlot.OFF_HAND,
            null, null
    };

    private static final String[] LABELS = {
            "头盔", "胸甲", "护腿", "靴子", null, "主手", "副手", null, null
    };

    /** Index of the informational panel. */
    private static final int INFO_SLOT = 4;

    /** Owning plugin, for scheduling repaints on the viewer's region thread. */
    private static volatile org.bukkit.plugin.Plugin schedulerPlugin;

    private final UUID dummyId;
    private final Inventory inventory;
    private final Registries registries;

    private DummyEquipmentMenu(ArmorStand dummy, Registries registries) {
        this.dummyId = dummy.getUniqueId();
        this.registries = registries;
        this.inventory = Bukkit.createInventory(this, LAYOUT.length,
                Component.text("训练假人装备", NamedTextColor.DARK_AQUA));
        refresh(dummy);
    }

    public static void open(Player player, ArmorStand dummy, Registries registries) {
        player.openInventory(new DummyEquipmentMenu(dummy, registries).getInventory());
    }

    /** Called once from {@code onEnable}; menus need a plugin to schedule with. */
    public static void init(org.bukkit.plugin.Plugin plugin) {
        schedulerPlugin = plugin;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public UUID dummyId() {
        return dummyId;
    }

    /**
     * Repaints every currently open view of {@code dummy}, not just the caller's.
     * The scan is over online players, which is O(players) per click and only
     * while menus are actually in use; a persistent viewer registry would need
     * close-event bookkeeping to stay correct, which costs more than it saves.
     * <p>
     * On Folia each repaint is pushed through the viewer's own scheduler, because
     * a player's inventory belongs to the region thread that owns them — which is
     * not necessarily the thread that owns this dummy. On Paper the entity
     * scheduler simply runs on the main thread, so behaviour is unchanged.
     */
    public static void refreshAll(ArmorStand dummy) {
        org.bukkit.plugin.Plugin plugin = schedulerPlugin;
        UUID id = dummy.getUniqueId();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getOpenInventory().getTopInventory().getHolder()
                    instanceof DummyEquipmentMenu menu && menu.dummyId.equals(id)) {
                if (plugin != null) {
                    mc506lw.lapisTargetDummy.util.FoliaScheduler.runAtEntity(
                            plugin, online, 0L, () -> menu.refresh(dummy));
                } else {
                    menu.refresh(dummy);
                }
            }
        }
    }

    /** @return the equipment slot a raw inventory slot maps to, or {@code null} */
    public static EquipmentSlot slotFor(int rawSlot) {
        return rawSlot >= 0 && rawSlot < LAYOUT.length ? LAYOUT[rawSlot] : null;
    }

    /** Repaints every slot from the dummy's current equipment. */
    public void refresh(ArmorStand dummy) {
        EntityEquipment equipment = dummy.getEquipment();
        for (int i = 0; i < LAYOUT.length; i++) {
            EquipmentSlot slot = LAYOUT[i];
            if (slot == null) {
                inventory.setItem(i, i == INFO_SLOT ? infoPanel(dummy) : decoration());
                continue;
            }
            ItemStack current = equipment == null ? null : equipment.getItem(slot);
            if (current != null && !current.getType().isAir()) {
                inventory.setItem(i, current.clone());
            } else {
                inventory.setItem(i, placeholder(LABELS[i]));
            }
        }
    }

    /** Empty-slot hint. Purely cosmetic: clicks are resolved against the dummy. */
    private static ItemStack placeholder(String label) {
        ItemStack item = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Component.text(label, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("放入任意物品", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    /** Informational panel: which category the dummy currently emulates. */
    private ItemStack infoPanel(ArmorStand dummy) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Component.text("假人状态", NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        // The same resolver the damage path uses, so the panel can never claim a
        // category that does not actually apply.
        DummyCategory category = DummyService.categoryOf(dummy, registries);
        meta.lore(List.of(
                Component.text("类别: ", NamedTextColor.GRAY)
                        .append(Component.text(category.displayName(), category.color()))
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("主手放入标记物可切换类别:", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("腐肉→亡灵  海龟壳→海洋", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("不祥旗帜→灾厄  蜘蛛眼→节肢", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack decoration() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Component.empty());
        item.setItemMeta(meta);
        return item;
    }
}
