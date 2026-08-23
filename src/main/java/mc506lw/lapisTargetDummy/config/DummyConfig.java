package mc506lw.lapisTargetDummy.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.IllegalFormatException;
import java.util.Locale;

/**
 * Immutable snapshot of config.yml.
 * <p>
 * Read once on enable / reload so no hot path ever touches the configuration
 * API (which is synchronized and allocates on every lookup).
 */
public final class DummyConfig {

    private static final String DEFAULT_FORMAT = "%.1f";

    public final Material baseBlock;

    // --- damage numbers ---
    public final int numberLifetimeTicks;
    public final double numberRiseHeight;
    public final double numberSpawnHeight;
    public final double numberSpreadRadius;
    public final float numberViewRange;
    public final float numberScale;
    public final int maxNumbersPerDummyPerSecond;
    public final boolean showOriginalDamage;
    public final boolean showCategoryMark;
    public final boolean fadeOut;
    public final String numberFormat;

    // --- behaviour ---
    public final int hurtImmunityTicks;
    public final boolean tickDummies;
    public final boolean showEnvironmentalDamage;
    public final boolean playHitSound;
    public final boolean requireSneakToOpenMenu;
    public final boolean cleanupOnDisable;

    private DummyConfig(FileConfiguration c) {
        Material base = Material.matchMaterial(c.getString("base-block", "NETHERITE_BLOCK"));
        // A non-block material would make the dummy uncreatable, so it is refused
        // as firmly as an unknown name.
        this.baseBlock = base == null || !base.isBlock() ? Material.NETHERITE_BLOCK : base;

        this.numberLifetimeTicks = clampInt(c.getInt("damage-numbers.lifetime-ticks", 16), 2, 200);
        this.numberRiseHeight = clampDouble(c.getDouble("damage-numbers.rise-height", 0.9D), 0.0D, 8.0D);
        this.numberSpawnHeight = clampDouble(c.getDouble("damage-numbers.spawn-height", 1.7D), 0.0D, 8.0D);
        this.numberSpreadRadius = clampDouble(c.getDouble("damage-numbers.spread-radius", 0.28D), 0.0D, 2.0D);
        this.numberViewRange = (float) clampDouble(c.getDouble("damage-numbers.view-range", 0.5D), 0.05D, 5.0D);
        this.numberScale = (float) clampDouble(c.getDouble("damage-numbers.scale", 1.6D), 0.1D, 10.0D);
        this.maxNumbersPerDummyPerSecond = clampInt(c.getInt("damage-numbers.max-per-dummy-per-second", 12), 1, 200);
        this.showOriginalDamage = c.getBoolean("damage-numbers.show-original", true);
        this.showCategoryMark = c.getBoolean("damage-numbers.show-category-mark", true);
        this.fadeOut = c.getBoolean("damage-numbers.fade-out", true);
        this.numberFormat = validFormat(c.getString("damage-numbers.format", DEFAULT_FORMAT));

        this.hurtImmunityTicks = clampInt(c.getInt("behaviour.hurt-immunity-ticks", 10), 0, 40);
        this.tickDummies = c.getBoolean("behaviour.tick-dummies", true);
        this.showEnvironmentalDamage = c.getBoolean("behaviour.show-environmental-damage", false);
        this.playHitSound = c.getBoolean("behaviour.play-hit-sound", true);
        this.requireSneakToOpenMenu = c.getBoolean("behaviour.require-sneak-to-open-menu", false);
        this.cleanupOnDisable = c.getBoolean("behaviour.cleanup-on-disable", true);
    }

    public static DummyConfig from(FileConfiguration configuration) {
        return new DummyConfig(configuration);
    }

    /**
     * Rejects a format string that would throw when applied to a double.
     * <p>
     * The format is used on every damage number, so an invalid one entered by
     * hand would otherwise raise an exception inside the damage event on every
     * single hit.
     */
    private static String validFormat(String format) {
        if (format == null) {
            return DEFAULT_FORMAT;
        }
        try {
            String.format(Locale.ROOT, format, 1.0D);
            return format;
        } catch (IllegalFormatException e) {
            return DEFAULT_FORMAT;
        }
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}