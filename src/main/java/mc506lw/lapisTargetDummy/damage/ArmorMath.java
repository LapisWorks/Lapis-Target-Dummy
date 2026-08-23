package mc506lw.lapisTargetDummy.damage;

/**
 * The vanilla damage-reduction formulas, as pure arithmetic.
 * <p>
 * Kept free of any Bukkit type so the numbers can be unit-tested without a
 * running server. {@link DamageEmulator} supplies the inputs it reads from the
 * world; the maths itself lives here.
 */
public final class ArmorMath {

    /** Vanilla damage per level for a category-matched weapon enchantment. */
    private static final double CATEGORY_ENCHANT_PER_LEVEL = 2.5D;

    /** Armor reduction is capped at 80%, i.e. 20 effective armor points. */
    private static final double MAX_EFFECTIVE_ARMOR = 20.0D;
    /** Protection is capped at 80% as well, at an EPF of 20. */
    private static final double MAX_EPF = 20.0D;

    private ArmorMath() {
    }

    /**
     * Armor and armor-toughness absorption.
     * <pre>
     * f = 2 + toughness / 4
     * g = clamp(armor - damage / f, armor * 0.2, 20)
     * result = damage * (1 - g / 25)
     * </pre>
     * Note that {@code g} depends on the incoming damage: high-damage hits punch
     * through armor proportionally better, which is why a flat percentage would
     * be wrong.
     */
    public static double applyArmor(double damage, double armor, double toughness) {
        if (damage <= 0.0D || armor <= 0.0D) {
            return Math.max(0.0D, damage);
        }
        double f = 2.0D + toughness / 4.0D;
        double g = clamp(armor - damage / f, armor * 0.2D, MAX_EFFECTIVE_ARMOR);
        return damage * (1.0D - g / 25.0D);
    }

    /**
     * Enchantment protection, applied after armor.
     * <pre>
     * result = damage * (1 - min(epf, 20) / 25)
     * </pre>
     */
    public static double applyProtection(double damage, double epf) {
        if (damage <= 0.0D || epf <= 0.0D) {
            return Math.max(0.0D, damage);
        }
        return damage * (1.0D - Math.min(epf, MAX_EPF) / 25.0D);
    }

    /**
     * Extra damage a category-matched weapon enchantment would have contributed.
     * Vanilla scales this by the attack-strength ratio but does not apply the
     * critical multiplier to it.
     */
    public static double categoryEnchantBonus(int level, double attackStrengthScale) {
        if (level <= 0) {
            return 0.0D;
        }
        return CATEGORY_ENCHANT_PER_LEVEL * level * Math.max(0.0D, attackStrengthScale);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
