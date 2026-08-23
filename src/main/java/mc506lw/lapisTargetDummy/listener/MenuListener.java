package mc506lw.lapisTargetDummy.listener;

import mc506lw.lapisTargetDummy.dummy.DummyService;
import mc506lw.lapisTargetDummy.ui.DummyEquipmentMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Applies equipment-menu edits to the dummy.
 * <p>
 * Every click inside the menu is cancelled and the item movement is performed
 * explicitly. Letting Bukkit resolve the click and mirroring the result
 * afterwards would be shorter, but it opens the door to desync and duplication
 * on the click types that touch several slots at once (shift-click,
 * double-click, number-key and offhand swaps, drags). Doing the exchange by hand
 * means exactly one item can move per click.
 * <p>
 * Just as importantly, the item taken out is read from the dummy's equipment, not
 * from the clicked inventory. The menu is a per-viewer copy, so two players with
 * the menu open on the same dummy — or one player whose view went stale after an
 * external equipment change — would otherwise each be handed a clone of the same
 * helmet. The dummy is the single source of truth, and the view is repainted from
 * it after every click.
 * <p>
 * The menu is recognised by its holder type, so no registry of open menus has to
 * stay in sync; the plugin closing every open view on disable and reload (see
 * {@code LapisTargetDummy#closeOpenMenus}) covers the orphaned-view case.
 */
public final class MenuListener implements Listener {

    private final DummyService dummies;

    public MenuListener(DummyService dummies) {
        this.dummies = dummies;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof DummyEquipmentMenu menu)) {
            return;
        }

        // Clicks in the player's own inventory are left alone, except the ones
        // that would move items into the menu without naming a target slot.
        if (event.getClickedInventory() != top) {
            if (event.getClick().isShiftClick() || event.getClick() == ClickType.DOUBLE_CLICK) {
                event.setCancelled(true);
            }
            return;
        }

        // From here on the menu owns the interaction entirely.
        event.setCancelled(true);

        HumanEntity viewer = event.getWhoClicked();
        int raw = event.getRawSlot();
        EquipmentSlot slot = DummyEquipmentMenu.slotFor(raw);
        if (slot == null) {
            return;
        }
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) {
            // Number-key, offhand-swap, middle-click, drop and shift variants all
            // imply a multi-slot or out-of-band move, which this layout cannot
            // represent.
            return;
        }

        ArmorStand dummy = resolve(menu.dummyId());
        if (dummy == null) {
            viewer.closeInventory();
            return;
        }
        EntityEquipment equipment = dummy.getEquipment();
        if (equipment == null) {
            viewer.closeInventory();
            return;
        }

        // Authoritative read: whatever the view happens to show, this is what the
        // dummy is actually wearing right now.
        ItemStack existing = equipment.getItem(slot);
        if (existing != null && existing.getType().isAir()) {
            existing = null;
        }

        ItemStack cursor = viewer.getItemOnCursor();
        boolean cursorEmpty = cursor.getType().isAir();
        if (!cursorEmpty && cursor.getAmount() <= 0) {
            // A zero-amount stack from a misbehaving plugin would mint an item
            // out of nothing below; refuse it outright.
            return;
        }

        if (cursorEmpty) {
            if (existing != null) {
                viewer.setItemOnCursor(existing.clone());
                write(equipment, slot, null);
            }
            DummyEquipmentMenu.refreshAll(dummy);
            return;
        }

        // Placing: exactly one item moves out of the cursor.
        ItemStack placed = cursor.clone();
        placed.setAmount(1);

        ItemStack remainder = cursor.clone();
        remainder.setAmount(cursor.getAmount() - 1);

        if (existing == null || cursor.getAmount() == 1) {
            // Empty target, or a clean one-for-one swap.
            viewer.setItemOnCursor(existing == null
                    ? (remainder.getAmount() <= 0 ? null : remainder)
                    : existing.clone());
        } else {
            // The cursor still holds a stack, so the displaced item cannot go onto
            // it; it is returned to the player instead of being destroyed.
            viewer.setItemOnCursor(remainder);
            giveBack(viewer, existing.clone());
        }
        write(equipment, slot, placed);
        DummyEquipmentMenu.refreshAll(dummy);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof DummyEquipmentMenu)) {
            return;
        }
        // A drag spreads items over several slots at once, so any drag that
        // touches the menu is rejected rather than partially applied.
        for (int raw : event.getRawSlots()) {
            if (raw < top.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /** Returns a displaced item to the player, dropping it only if there is no room. */
    private static void giveBack(HumanEntity viewer, ItemStack item) {
        if (!viewer.getInventory().addItem(item).isEmpty()) {
            viewer.getWorld().dropItemNaturally(viewer.getLocation(), item);
            if (viewer instanceof Player player) {
                player.sendActionBar(Component.text("背包已满，替换下的物品已掉落", NamedTextColor.YELLOW));
            }
        }
    }

    private static void write(EntityEquipment equipment, EquipmentSlot slot, ItemStack item) {
        equipment.setItem(slot, item == null || item.getType().isAir() ? null : item.clone(), true);
    }

    private ArmorStand resolve(UUID dummyId) {
        Entity entity = Bukkit.getEntity(dummyId);
        if (entity instanceof ArmorStand stand && stand.isValid() && dummies.isDummy(stand)) {
            return stand;
        }
        return null;
    }
}
