package mc506lw.lapisTargetDummy.damage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the reduction maths against values derived from the vanilla formulas.
 * <p>
 * These are the numbers the plugin puts on screen, so an error here would be
 * silently misleading rather than visibly broken — which is exactly why they are
 * tested rather than eyeballed in-game.
 */
class ArmorMathTest {

    private static final double EPSILON = 1.0E-6D;

    @Test
    @DisplayName("无护甲时伤害不变")
    void noArmorLeavesDamageUnchanged() {
        assertEquals(10.0D, ArmorMath.applyArmor(10.0D, 0.0D, 0.0D), EPSILON);
    }

    @Test
    @DisplayName("整套铁甲(15点)对10点伤害的减免")
    void ironArmorReducesTenDamage() {
        // f = 2, g = clamp(15 - 10/2, 3, 20) = 10, so 10 * (1 - 10/25) = 6.
        assertEquals(6.0D, ArmorMath.applyArmor(10.0D, 15.0D, 0.0D), EPSILON);
    }

    @Test
    @DisplayName("下界合金甲(20点护甲/12点韧性)对10点伤害的减免")
    void netheriteArmorReducesTenDamage() {
        // f = 2 + 12/4 = 5, g = clamp(20 - 10/5, 4, 20) = 18,
        // so 10 * (1 - 18/25) = 2.8.
        assertEquals(2.8D, ArmorMath.applyArmor(10.0D, 20.0D, 12.0D), EPSILON);
    }

    @Test
    @DisplayName("高伤害更容易穿透护甲")
    void highDamagePenetratesArmorProportionallyBetter() {
        double lowRatio = ArmorMath.applyArmor(4.0D, 20.0D, 12.0D) / 4.0D;
        double highRatio = ArmorMath.applyArmor(40.0D, 20.0D, 12.0D) / 40.0D;
        assertTrue(highRatio > lowRatio,
                "护甲减免比例应随伤害升高而下降: low=" + lowRatio + " high=" + highRatio);
    }

    @Test
    @DisplayName("护甲减免下限为护甲值的20%")
    void armorReductionHasFloorOfTwentyPercentOfArmor() {
        // A huge hit drives armor - damage/f negative, so the 0.2 * armor floor
        // applies: g = 4 for 20 armor, leaving 1 - 4/25 = 84%.
        double damage = 1000.0D;
        assertEquals(damage * 0.84D, ArmorMath.applyArmor(damage, 20.0D, 12.0D), 1.0E-9D);
    }

    @Test
    @DisplayName("护甲减免上限为80%")
    void armorReductionCapsAtEightyPercent() {
        // 40 armor would give g = 40 - small, but g is capped at 20 → 1 - 20/25.
        assertEquals(2.0D, ArmorMath.applyArmor(10.0D, 40.0D, 20.0D), EPSILON);
    }

    @Test
    @DisplayName("保护IV全套(EPF 16)的减免")
    void protectionFourFullSet() {
        // 4 pieces x Protection IV = EPF 16, so 1 - 16/25 = 36% remaining.
        assertEquals(10.0D * 0.36D, ArmorMath.applyProtection(10.0D, 16.0D), EPSILON);
    }

    @Test
    @DisplayName("保护减免上限为80%")
    void protectionCapsAtEightyPercent() {
        assertEquals(2.0D, ArmorMath.applyProtection(10.0D, 40.0D), EPSILON);
    }

    @Test
    @DisplayName("类别附魔每级2.5点，并随攻击强度缩放")
    void categoryEnchantBonusScales() {
        assertEquals(12.5D, ArmorMath.categoryEnchantBonus(5, 1.0D), EPSILON);
        assertEquals(6.25D, ArmorMath.categoryEnchantBonus(5, 0.5D), EPSILON);
        assertEquals(0.0D, ArmorMath.categoryEnchantBonus(0, 1.0D), EPSILON);
    }

    @Test
    @DisplayName("零和负伤害不会产生负数结果")
    void nonPositiveDamageStaysNonNegative() {
        assertEquals(0.0D, ArmorMath.applyArmor(0.0D, 20.0D, 12.0D), EPSILON);
        assertEquals(0.0D, ArmorMath.applyArmor(-5.0D, 20.0D, 12.0D), EPSILON);
        assertEquals(0.0D, ArmorMath.applyProtection(-5.0D, 16.0D), EPSILON);
    }

    @Test
    @DisplayName("护甲与保护叠加顺序: 先护甲后保护")
    void armorThenProtectionCompose() {
        double afterArmor = ArmorMath.applyArmor(20.0D, 20.0D, 12.0D);
        double afterProtection = ArmorMath.applyProtection(afterArmor, 16.0D);
        // f = 5, g = clamp(20 - 4, 4, 20) = 16 → 20 * 0.36 = 7.2;
        // then 7.2 * 0.36 = 2.592.
        assertEquals(7.2D, afterArmor, EPSILON);
        assertEquals(2.592D, afterProtection, EPSILON);
    }
}
