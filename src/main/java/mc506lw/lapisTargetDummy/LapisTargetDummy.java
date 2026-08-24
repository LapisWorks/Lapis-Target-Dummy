package mc506lw.lapisTargetDummy;

import mc506lw.lapisTargetDummy.command.DummyCommand;
import mc506lw.lapisTargetDummy.config.DummyConfig;
import mc506lw.lapisTargetDummy.damage.AttackStrengthTracker;
import mc506lw.lapisTargetDummy.damage.DamageEmulator;
import mc506lw.lapisTargetDummy.damage.DamageNumberRenderer;
import mc506lw.lapisTargetDummy.dummy.DummyKeys;
import mc506lw.lapisTargetDummy.dummy.DummyService;
import mc506lw.lapisTargetDummy.listener.BlockBreakListener;
import mc506lw.lapisTargetDummy.listener.DamageListener;
import mc506lw.lapisTargetDummy.listener.InteractListener;
import mc506lw.lapisTargetDummy.listener.MenuListener;
import mc506lw.lapisTargetDummy.listener.PlacementListener;
import mc506lw.lapisTargetDummy.listener.ProtectionListener;
import mc506lw.lapisTargetDummy.ui.DummyEquipmentMenu;
import mc506lw.lapisTargetDummy.util.Registries;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Training dummy plugin.
 * <p>
 * Placing an armor stand on a netherite block turns it into a dummy. Hitting it
 * shows a rising damage number reflecting what the hit would really have done to
 * a mob wearing that armor, and the marker item in its main hand decides which
 * creature category the damage is calculated against.
 * <p>
 * Design notes worth knowing before changing anything here:
 * <ul>
 *   <li>Dummy state lives in entity PDCs, so enable and disable are O(1), no data
 *       file is written and no startup scan is performed.</li>
 *   <li>No repeating task runs unless damage numbers are currently on screen.</li>
 *   <li>Damage is simulated, never applied: the damage event is cancelled, so
 *       dummies are indestructible and weapons take no durability loss.</li>
 * </ul>
 */
public final class LapisTargetDummy extends JavaPlugin {

    private DummyConfig config;

    private DummyService dummies;
    private DamageNumberRenderer renderer;
    private AttackStrengthTracker strengthTracker;

    private DamageListener damageListener;
    private InteractListener interactListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = DummyConfig.from(getConfig());
        DummyKeys keys = new DummyKeys(this);
        Registries registries = new Registries();

        if (registries.armor == null) {
            getLogger().warning("未能解析护甲属性，减伤计算将退化为不减伤。"
                    + "请确认服务端版本为 1.21 或更高。");
        }
        if (mc506lw.lapisTargetDummy.util.FoliaScheduler.isFolia()) {
            getLogger().info("检测到 Folia：所有延迟任务走实体区域调度器。");
        }

        DummyEquipmentMenu.init(this);
        this.dummies = new DummyService(keys, registries, config);
        this.renderer = new DamageNumberRenderer(this, keys, config);
        this.strengthTracker = new AttackStrengthTracker();

        DamageEmulator emulator = new DamageEmulator(registries, strengthTracker);

        this.damageListener = new DamageListener(dummies, emulator, renderer, strengthTracker, config);
        this.interactListener = new InteractListener(dummies, registries, config);

        getServer().getPluginManager().registerEvents(new PlacementListener(this, dummies), this);
        getServer().getPluginManager().registerEvents(damageListener, this);
        getServer().getPluginManager().registerEvents(interactListener, this);
        getServer().getPluginManager().registerEvents(new ProtectionListener(this, dummies, keys), this);
        getServer().getPluginManager().registerEvents(new MenuListener(dummies), this);
        getServer().getPluginManager().registerEvents(new BlockBreakListener(dummies, config), this);

        PluginCommand command = getCommand("ltd");
        if (command != null) {
            DummyCommand executor = new DummyCommand(this, dummies, registries);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
    }

    @Override
    public void onDisable() {
        closeOpenMenus();
        if (renderer != null) {
            if (config != null && config.cleanupOnDisable) {
                renderer.shutdown();
            } else {
                // The numbers are non-persistent entities, so leaving them behind
                // costs nothing: the server discards them and never writes them to
                // disk. Only the tracking state and the sweeper are dropped.
                renderer.stopTasks();
            }
        }
        if (strengthTracker != null) {
            strengthTracker.clear();
        }
    }

    /** Re-reads config.yml and pushes the new snapshot into every component. */
    public void reloadPlugin() {
        closeOpenMenus();
        reloadConfig();
        this.config = DummyConfig.from(getConfig());

        renderer.shutdown();
        renderer.applyConfig(config);
        dummies.applyConfig(config);
        damageListener.applyConfig(config);
        interactListener.applyConfig(config);
    }

    /**
     * Closes every equipment menu currently open. Called on disable and on
     * reload, because an orphaned menu view is indistinguishable from a chest
     * full of free copies of the dummy's equipment.
     * <p>
     * Each close rides the viewer's own scheduler: a player's inventory belongs
     * to their region thread under Folia, and onDisable runs on none of them.
     */
    private void closeOpenMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof DummyEquipmentMenu) {
                mc506lw.lapisTargetDummy.util.FoliaScheduler.runAtEntity(this, player, 0L,
                        player::closeInventory);
            }
        }
    }
}