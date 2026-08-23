package mc506lw.lapisTargetDummy.damage;

import com.google.common.collect.Multimap;
import mc506lw.lapisTargetDummy.util.Registries;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Armor and armor-toughness totals for a dummy's current equipment.
 * <p>
 * Values are derived from the item attribute modifiers rather than a hardcoded
 * table, so custom or datapack-modified gear is accounted for automatically.
 * Item-defined modifiers take priority over the material defaults, matching how
 * the server resolves them.
 * <p>
 * This is computed on hit, not cached: it is four item lookups and no
 * allocation-heavy work, and it is always consistent with what the dummy is
 * actually wearing (a cache would need an equipment listener and would go stale
 * when {@code canTick(false)} suppresses attribute refreshes).
 */
public record ArmorStats(double armor, double toughness) {

    private static final ArmorStats EMPTY = new ArmorStats(0.0D, 0.0D);

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    public static ArmorStats of(EntityEquipment equipment, Registries registries) {
        if (equipment == null || registries.armor == null) {
            return EMPTY;
        }
        double armor = 0.0D;
        double toughness = 0.0D;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack item = equipment.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            armor += modifierTotal(item, slot, registries.armor);
            if (registries.armorToughness != null) {
                toughness += modifierTotal(item, slot, registries.armorToughness);
            }
        }
        return armor <= 0.0D && toughness <= 0.0D ? EMPTY : new ArmorStats(armor, toughness);
    }

    /**
     * Sums the flat contribution of one item for one attribute. Only
     * {@code ADD_NUMBER} is summed; multiplicative operations are ignored
     * because they would need the whole vanilla attribute pipeline to apply
     * correctly, and no vanilla armor piece uses them.
     */
    private static double modifierTotal(ItemStack item, EquipmentSlot slot, Attribute attribute) {
        Multimap<Attribute, AttributeModifier> modifiers = null;
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.hasAttributeModifiers()) {
                modifiers = meta.getAttributeModifiers(slot);
            }
        }
        if (modifiers == null) {
            modifiers = item.getType().getDefaultAttributeModifiers(slot);
        }
        if (modifiers == null || modifiers.isEmpty()) {
            return 0.0D;
        }
        double total = 0.0D;
        for (AttributeModifier modifier : modifiers.get(attribute)) {
            if (modifier.getOperation() == AttributeModifier.Operation.ADD_NUMBER) {
                total += modifier.getAmount();
            }
        }
        return total;
    }
}
