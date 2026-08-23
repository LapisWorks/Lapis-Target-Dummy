package mc506lw.lapisTargetDummy.util;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.banner.PatternType;
import org.bukkit.enchantments.Enchantment;

/**
 * Version-tolerant registry lookups.
 * <p>
 * Across the 1.21.x line Bukkit renamed the {@code Attribute} constants
 * ({@code GENERIC_ARMOR} to {@code ARMOR}) and turned {@code Attribute},
 * {@code Enchantment} and {@code PatternType} from enums into interfaces.
 * Referencing those constants directly would bind this plugin to a single patch
 * release, so every lookup goes through the registry using the stable namespaced
 * id instead. Everything is resolved once at startup into final fields, so the
 * hot path is a field read.
 */
public final class Registries {

    public final Attribute armor;
    public final Attribute armorToughness;

    public final Enchantment protection;
    public final Enchantment fireProtection;
    public final Enchantment blastProtection;
    public final Enchantment projectileProtection;

    public final Enchantment smite;
    public final Enchantment baneOfArthropods;
    public final Enchantment impaling;

    public final PatternType rhombus;
    public final PatternType stripeMiddle;

    public Registries() {
        Registry<Attribute> attributes = registry(RegistryKey.ATTRIBUTE);
        Registry<Enchantment> enchantments = registry(RegistryKey.ENCHANTMENT);
        Registry<PatternType> patterns = registry(RegistryKey.BANNER_PATTERN);

        // "armor" since 1.21.2, "generic.armor" before that.
        this.armor = lookup(attributes, "armor", "generic.armor");
        this.armorToughness = lookup(attributes, "armor_toughness", "generic.armor_toughness");

        this.protection = lookup(enchantments, "protection");
        this.fireProtection = lookup(enchantments, "fire_protection");
        this.blastProtection = lookup(enchantments, "blast_protection");
        this.projectileProtection = lookup(enchantments, "projectile_protection");

        this.smite = lookup(enchantments, "smite");
        this.baneOfArthropods = lookup(enchantments, "bane_of_arthropods");
        this.impaling = lookup(enchantments, "impaling");

        this.rhombus = lookup(patterns, "rhombus");
        this.stripeMiddle = lookup(patterns, "stripe_middle");
    }

    private static <T extends Keyed> Registry<T> registry(RegistryKey<T> key) {
        try {
            return RegistryAccess.registryAccess().getRegistry(key);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /** Tries each id in order and returns the first that resolves. */
    private static <T extends Keyed> T lookup(Registry<T> registry, String... ids) {
        if (registry == null) {
            return null;
        }
        for (String id : ids) {
            try {
                T value = registry.get(NamespacedKey.minecraft(id));
                if (value != null) {
                    return value;
                }
            } catch (RuntimeException | LinkageError ignored) {
                // Try the next candidate id.
            }
        }
        return null;
    }
}
