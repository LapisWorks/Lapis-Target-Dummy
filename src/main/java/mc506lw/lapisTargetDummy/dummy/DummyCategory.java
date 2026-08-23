package mc506lw.lapisTargetDummy.dummy;

import mc506lw.lapisTargetDummy.util.Registries;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.block.banner.Pattern;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * The creature category a dummy is pretending to be, selected by the marker
 * item held in its main hand.
 * <p>
 * {@code EntityCategory} is a fixed property of the entity type and cannot be
 * changed through the API, so the category only exists as a plugin-side concept
 * that feeds the damage emulation. It is resolved lazily on hit, which means no
 * listener, no cache and no memory cost while the dummy is idle.
 */
public enum DummyCategory {

    NONE("", NamedTextColor.WHITE, "普通"),
    UNDEAD("\u2620", NamedTextColor.DARK_GREEN, "亡灵生物"),
    WATER("\u224B", NamedTextColor.AQUA, "海洋生物"),
    ILLAGER("\u2694", NamedTextColor.DARK_GRAY, "灾厄村民"),
    ARTHROPOD("\u2698", NamedTextColor.YELLOW, "节肢生物");

    /** Translation key the ominous banner carries as its item name since 1.20.5. */
    private static final String OMINOUS_BANNER_KEY = "block.minecraft.ominous_banner";

    private final String mark;
    private final NamedTextColor color;
    private final String displayName;

    DummyCategory(String mark, NamedTextColor color, String displayName) {
        this.mark = mark;
        this.color = color;
        this.displayName = displayName;
    }

    public String mark() {
        return mark;
    }

    public NamedTextColor color() {
        return color;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * Resolves the category from a marker item. Only the main hand is consulted
     * by the caller, so the mapping stays unambiguous.
     */
    public static DummyCategory fromMarker(ItemStack item, Registries registries) {
        if (item == null || item.getType().isAir()) {
            return NONE;
        }
        Material type = item.getType();
        if (type == Material.ROTTEN_FLESH) {
            return UNDEAD;
        }
        // Both the shell and the helmet read as aquatic: the request named the
        // shell, and a player reaching for "the turtle item" may grab either.
        if (type == Material.TURTLE_HELMET || type == Material.TURTLE_SCUTE) {
            return WATER;
        }
        if (type == Material.SPIDER_EYE) {
            return ARTHROPOD;
        }
        if (isOminousBanner(item, registries)) {
            return ILLAGER;
        }
        return NONE;
    }

    /**
     * The ominous banner is not its own {@link Material}: it is a white banner
     * with a fixed pattern set. Since 1.20.5 it also carries a translatable
     * item name, which is the cheapest and most reliable signal. The pattern
     * heuristic stays as a fallback for banners produced by other means.
     */
    private static boolean isOminousBanner(ItemStack item, Registries registries) {
        Material type = item.getType();
        if (type != Material.WHITE_BANNER && type != Material.WHITE_WALL_BANNER) {
            return false;
        }
        if (!item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        if (meta.hasItemName() && isOminousName(meta.itemName())) {
            return true;
        }
        if (meta.hasDisplayName() && isOminousName(meta.displayName())) {
            return true;
        }
        if (!(meta instanceof BannerMeta banner)) {
            return false;
        }
        return matchesOminousPatterns(banner, registries);
    }

    private static boolean isOminousName(Component component) {
        return component instanceof TranslatableComponent translatable
                && OMINOUS_BANNER_KEY.equals(translatable.key());
    }

    /**
     * Deliberately lenient: requires the two patterns unique to the illager
     * banner rather than an exact ordered match, so a pattern list reordered by
     * a future version or another plugin still resolves correctly.
     */
    private static boolean matchesOminousPatterns(BannerMeta banner, Registries registries) {
        if (registries.rhombus == null || registries.stripeMiddle == null) {
            return false;
        }
        List<Pattern> patterns = banner.getPatterns();
        if (patterns.size() < 6) {
            return false;
        }
        boolean cyanRhombus = false;
        boolean blackStripeMiddle = false;
        for (Pattern pattern : patterns) {
            if (pattern.getColor() == DyeColor.CYAN && pattern.getPattern() == registries.rhombus) {
                cyanRhombus = true;
            } else if (pattern.getColor() == DyeColor.BLACK && pattern.getPattern() == registries.stripeMiddle) {
                blackStripeMiddle = true;
            }
        }
        return cyanRhombus && blackStripeMiddle;
    }
}
