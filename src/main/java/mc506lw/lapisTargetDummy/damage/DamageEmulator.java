package mc506lw.lapisTargetDummy.damage;

import mc506lw.lapisTargetDummy.dummy.DummyCategory;
import mc506lw.lapisTargetDummy.util.Registries;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

import java.util.EnumSet;
import java.util.Set;

/**
 * Reconstructs what a real mob of the emulated category would have taken.
 * <p>
 * The strategy is to reuse, not replace, the vanilla pipeline. By the time the
 * damage event fires the server has already folded in the weapon's attack
 * damage, Sharpness, Strength, the critical multiplier and the attack-cooldown
 * scaling. Two things it necessarily gets wrong for an armor stand:
 * <ul>
 *   <li>category-specific weapon enchantments (Smite, Bane of Arthropods,
 *       Impaling) contribute nothing, because an armor stand's
 *       {@link org.bukkit.entity.EntityCategory} is always {@code NONE};</li>
 *   <li>armor stands skip armor absorption entirely, so wearing gear changes
 *       nothing.</li>
 * </ul>
 * Both are corrected here with the vanilla formulas.
 */
public final class DamageEmulator {

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    /**
     * Damage types in vanilla's {@code #minecraft:bypasses_armor} tag. Armor
     * points and toughness do nothing against these; Protection is not skipped,
     * because vanilla still applies it.
     */
    private static final Set<EntityDamageEvent.DamageCause> BYPASSES_ARMOR = EnumSet.of(
            // Entity-caused:
            EntityDamageEvent.DamageCause.MAGIC,
            EntityDamageEvent.DamageCause.THORNS,
            EntityDamageEvent.DamageCause.SONIC_BOOM,
            EntityDamageEvent.DamageCause.WITHER,
            EntityDamageEvent.DamageCause.DRAGON_BREATH,
            // Environmental, reachable when show-environmental-damage is on:
            EntityDamageEvent.DamageCause.FALL,
            EntityDamageEvent.DamageCause.FIRE_TICK,
            EntityDamageEvent.DamageCause.DROWNING,
            EntityDamageEvent.DamageCause.STARVATION,
            EntityDamageEvent.DamageCause.SUFFOCATION,
            EntityDamageEvent.DamageCause.FREEZE,
            EntityDamageEvent.DamageCause.CRAMMING,
            EntityDamageEvent.DamageCause.FLY_INTO_WALL,
            EntityDamageEvent.DamageCause.WORLD_BORDER);

    private final Registries registries;
    private final AttackStrengthTracker strengthTracker;

    public DamageEmulator(Registries registries, AttackStrengthTracker strengthTracker) {
        this.registries = registries;
        this.strengthTracker = strengthTracker;
    }

    /**
     * @param dummy    the target armor stand
     * @param event    the damage event, read at {@code HIGHEST} so other plugins
     *                 have already adjusted the base damage
     * @param category the category resolved from the dummy's marker item
     */
    public DamageResult simulate(ArmorStand dummy, EntityDamageEvent event, DummyCategory category) {
        boolean critical = event instanceof EntityDamageByEntityEvent byEntity && byEntity.isCritical();
        Entity damager = event instanceof EntityDamageByEntityEvent byEntity ? byEntity.getDamager() : null;

        // getDamage() at HIGHEST priority is the weapon damage after the server
        // folded in enchantments, potions, the critical multiplier and the
        // attack-cooldown scaling, plus any adjustment other plugins made.
        double base = event.getDamage();
        double raw = base + categoryEnchantBonus(damager, category);

        // Armor stands skip armor absorption in vanilla, so getFinalDamage()
        // normally equals getDamage(). If a fork or another plugin did reduce it,
        // that result is respected rather than reducing a second time.
        double serverReduction = base - event.getFinalDamage();
        if (serverReduction > 0.01D) {
            return new DamageResult(raw, Math.max(0.0D, raw - serverReduction), category, critical);
        }

        double finalized = applyArmor(dummy, raw, event.getCause());
        return new DamageResult(raw, Math.max(0.0D, finalized), category, critical);
    }

    /**
     * Damage the attacker's weapon would have added against this category.
     * <p>
     * Vanilla scales the enchantment bonus linearly with the attack-strength
     * scale, so the value recorded before the swing is applied here. It is not
     * multiplied by the critical bonus, which in vanilla only affects the
     * weapon's base damage.
     */
    private double categoryEnchantBonus(Entity damager, DummyCategory category) {
        if (category == DummyCategory.NONE) {
            return 0.0D;
        }
        Enchantment enchantment = categoryEnchantment(category);
        if (enchantment == null) {
            return 0.0D;
        }
        ItemStack weapon = weaponOf(damager);
        if (weapon == null || weapon.getType().isAir()) {
            return 0.0D;
        }
        int level = weapon.getEnchantmentLevel(enchantment);
        if (level <= 0) {
            return 0.0D;
        }
        double scale = damager instanceof Player player ? strengthTracker.scaleFor(player) : 1.0D;
        return ArmorMath.categoryEnchantBonus(level, scale);
    }

    private Enchantment categoryEnchantment(DummyCategory category) {
        return switch (category) {
            case UNDEAD -> registries.smite;
            case ARTHROPOD -> registries.baneOfArthropods;
            case WATER -> registries.impaling;
            // No vanilla weapon enchantment targets illagers, so this category
            // is display-only as far as damage is concerned.
            case ILLAGER, NONE -> null;
        };
    }

    /**
     * Resolves the item that dealt the damage.
     * <p>
     * A thrown trident has already left the shooter's hand, so its own item stack
     * is the only place Impaling can be read from; falling back to the shooter's
     * main hand would under-report every ranged trident hit. Other projectiles
     * (arrows, snowballs) carry no enchantments that matter here, so their
     * shooter's hand is consulted instead.
     */
    private static ItemStack weaponOf(Entity damager) {
        // getItemStack() (not the for-removal getItem()) is the current accessor
        // for a flying arrow's or trident's item.
        if (damager instanceof AbstractArrow arrow) {
            return arrow.getItemStack();
        }
        if (damager instanceof LivingEntity living) {
            EntityEquipment equipment = living.getEquipment();
            return equipment == null ? null : equipment.getItemInMainHand();
        }
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof LivingEntity living) {
            EntityEquipment equipment = living.getEquipment();
            return equipment == null ? null : equipment.getItemInMainHand();
        }
        return null;
    }

    /**
     * Vanilla armor absorption followed by protection enchantments. The formulas
     * themselves live in {@link ArmorMath} so they can be unit-tested without a
     * server.
     */
    private double applyArmor(ArmorStand dummy, double damage, EntityDamageEvent.DamageCause cause) {
        if (damage <= 0.0D) {
            return 0.0D;
        }
        EntityEquipment equipment = dummy.getEquipment();

        double result = damage;
        // Some damage types are in vanilla's #bypasses_armor tag: armor points and
        // toughness do nothing against them, but Protection still applies.
        if (!BYPASSES_ARMOR.contains(cause)) {
            ArmorStats stats = ArmorStats.of(equipment, registries);
            result = ArmorMath.applyArmor(result, stats.armor(), stats.toughness());
        }
        return ArmorMath.applyProtection(result, protectionFactor(equipment, cause));
    }

    /**
     * Enchantment protection factor summed over the four armor slots. Generic
     * Protection counts once per level; the specialised variants count double
     * against the damage type they cover, matching vanilla.
     */
    private double protectionFactor(EntityEquipment equipment, EntityDamageEvent.DamageCause cause) {
        if (equipment == null) {
            return 0.0D;
        }
        Enchantment specific = specificProtection(cause);
        double epf = 0.0D;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack piece = equipment.getItem(slot);
            if (piece == null || piece.getType().isAir() || !piece.hasItemMeta()) {
                continue;
            }
            if (registries.protection != null) {
                epf += piece.getEnchantmentLevel(registries.protection);
            }
            if (specific != null) {
                epf += piece.getEnchantmentLevel(specific) * 2.0D;
            }
        }
        return epf;
    }

    private Enchantment specificProtection(EntityDamageEvent.DamageCause cause) {
        return switch (cause) {
            case PROJECTILE -> registries.projectileProtection;
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> registries.blastProtection;
            // Fire Protection also covers fire set by a mob (blaze fireball,
            // Flame bow), which does arrive here as entity damage. The purely
            // environmental variants (FIRE_TICK, LAVA, HOT_FLOOR, CAMPFIRE) are
            // cancelled before the emulator ever runs.
            case FIRE -> registries.fireProtection;
            default -> null;
        };
    }
}
