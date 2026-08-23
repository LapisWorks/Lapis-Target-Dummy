package mc506lw.lapisTargetDummy.damage;

import mc506lw.lapisTargetDummy.dummy.DummyCategory;

/**
 * Outcome of one simulated hit on a dummy.
 *
 * @param raw       damage before the dummy's armor is taken into account, but
 *                  after the weapon, its enchantments and the category bonus
 * @param finalized damage the dummy would actually take after armor, toughness
 *                  and protection enchantments
 * @param category  category the dummy was emulating for this hit
 * @param critical  whether the server flagged the hit as a critical
 */
public record DamageResult(double raw, double finalized, DummyCategory category, boolean critical) {
}
