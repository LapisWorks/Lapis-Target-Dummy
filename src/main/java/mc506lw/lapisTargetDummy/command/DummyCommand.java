package mc506lw.lapisTargetDummy.command;

import mc506lw.lapisTargetDummy.LapisTargetDummy;
import mc506lw.lapisTargetDummy.damage.ArmorStats;
import mc506lw.lapisTargetDummy.dummy.DummyCategory;
import mc506lw.lapisTargetDummy.dummy.DummyService;
import mc506lw.lapisTargetDummy.util.Registries;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * {@code /ltd} — reload, inspect.
 */
public final class DummyCommand implements CommandExecutor, TabCompleter {

    /** How far to look for the dummy the player is aiming at. */
    private static final int TARGET_RANGE = 6;

    private static final List<String> SUBCOMMANDS = List.of("reload", "info", "help");

    private final LapisTargetDummy plugin;
    private final DummyService dummies;
    private final Registries registries;

    public DummyCommand(LapisTargetDummy plugin, DummyService dummies, Registries registries) {
        this.plugin = plugin;
        this.dummies = dummies;
        this.registries = registries;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                if (!sender.hasPermission("lapistargetdummy.admin")) {
                    sender.sendMessage(Component.text("你没有权限。", NamedTextColor.RED));
                    return true;
                }
                plugin.reloadPlugin();
                sender.sendMessage(Component.text("配置已重载。", NamedTextColor.GREEN));
            }
            case "info" -> info(sender);
            default -> sendHelp(sender, label);
        }
        return true;
    }

    private void info(CommandSender sender) {
        ArmorStand dummy = targetOf(sender);
        if (dummy == null) {
            return;
        }
        DummyCategory category = dummies.categoryOf(dummy);
        ArmorStats stats = ArmorStats.of(dummy.getEquipment(), registries);

        sender.sendMessage(Component.text("── 训练假人 ──", NamedTextColor.DARK_AQUA));
        sender.sendMessage(Component.text("类别: ", NamedTextColor.GRAY)
                .append(Component.text(category.displayName(), category.color())));
        sender.sendMessage(Component.text("护甲: ", NamedTextColor.GRAY)
                .append(Component.text(String.format(Locale.ROOT, "%.1f", stats.armor()), NamedTextColor.WHITE))
                .append(Component.text("  韧性: ", NamedTextColor.GRAY))
                .append(Component.text(String.format(Locale.ROOT, "%.1f", stats.toughness()), NamedTextColor.WHITE)));
    }

    /** Resolves the dummy the sender is looking at, reporting failures itself. */
    private ArmorStand targetOf(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("该子命令只能由玩家使用。", NamedTextColor.RED));
            return null;
        }
        Entity target = player.getTargetEntity(TARGET_RANGE);
        if (!(target instanceof ArmorStand stand) || !dummies.isDummy(stand)) {
            player.sendMessage(Component.text("请先看向一个训练假人。", NamedTextColor.RED));
            return null;
        }
        return stand;
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(Component.text("── 训练假人 ──", NamedTextColor.DARK_AQUA));
        sender.sendMessage(Component.text("在下界合金块上放置盔甲架即可创建假人。", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("右键打开装备界面。", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("空手潜行攻击可拆除假人。", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/" + label + " info", NamedTextColor.WHITE)
                .append(Component.text(" 查看当前假人状态", NamedTextColor.DARK_GRAY)));
        sender.sendMessage(Component.text("/" + label + " reload", NamedTextColor.WHITE)
                .append(Component.text(" 重载配置", NamedTextColor.DARK_GRAY)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(SUBCOMMANDS.stream(), args[0]);
        }
        return List.of();
    }

    private static List<String> filter(Stream<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.filter(option -> option.startsWith(lower)).toList();
    }
}