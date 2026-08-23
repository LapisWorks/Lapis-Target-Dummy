package mc506lw.lapisTargetDummy.dummy;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * All {@link NamespacedKey}s used by this plugin, created once at startup.
 * <p>
 * Persistent state lives entirely inside entity PDCs, so the plugin needs no
 * save files and no startup scanning.
 */
public final class DummyKeys {

    /** Marks an armor stand as a training dummy. Value is the schema version. */
    public final NamespacedKey dummy;
    /** Marks a damage-number display as plugin-owned and disposable. */
    public final NamespacedKey number;
    /** Links a head display back to its owning dummy (UUID string). */
    public final NamespacedKey owner;

    public DummyKeys(Plugin plugin) {
        this.dummy = new NamespacedKey(plugin, "dummy");
        this.number = new NamespacedKey(plugin, "number");
        this.owner = new NamespacedKey(plugin, "owner");
    }
}
