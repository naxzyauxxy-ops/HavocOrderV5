package net.eclipse.havocorders.command;

import net.eclipse.havocorders.HavocOrders;
import net.eclipse.havocorders.dialog.OrdersScreen;
import net.eclipse.havocorders.storage.LegacyImporter;
import net.eclipse.havocorders.util.Text;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One entry point. Everything else lives inside the dialogs, so there are no
 * sub-commands for your orders, collecting, or selling.
 */
public class OrdersCommand implements CommandExecutor, TabCompleter {

    private final HavocOrders plugin;

    public OrdersCommand(HavocOrders plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("havocorders.admin")) {
                sender.sendMessage(Text.component(plugin.message("NO-PERMISSION")));
                return true;
            }
            plugin.reloadEverything();
            sender.sendMessage(Text.component(plugin.message("RELOADED")));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("import")) {
            if (!sender.hasPermission("havocorders.admin")) {
                sender.sendMessage(Text.component(plugin.message("NO-PERMISSION")));
                return true;
            }
            File file = args.length > 1
                    ? new File(plugin.getDataFolder(), args[1])
                    : plugin.importer().defaultFile();
            if (!file.isFile()) {
                sender.sendMessage(Text.component("&cNo such file: " + file.getPath()));
                return true;
            }
            try {
                LegacyImporter.Report report = plugin.importer().importFrom(file);
                for (String line : plugin.importer().summary(report)) {
                    sender.sendMessage(Text.component("&#f40d0d" + line));
                }
                sender.sendMessage(Text.component("&7No money was moved and no loot was dropped."));
            } catch (Exception ex) {
                sender.sendMessage(Text.component("&cImport failed: " + ex.getMessage()
                        + " - see the console. Nothing was changed."));
            }
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("spawners")) {
            if (!sender.hasPermission("havocorders.admin")) {
                sender.sendMessage(Text.component(plugin.message("NO-PERMISSION")));
                return true;
            }
            return handleSpawners(sender, args);
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.component(plugin.message("PLAYERS-ONLY")));
            return true;
        }
        if (!player.hasPermission("havocorders.use")) {
            player.sendMessage(Text.component(plugin.message("NO-PERMISSION")));
            return true;
        }
        if (!plugin.economy().isReady()) {
            player.sendMessage(Text.component(plugin.message("NO-ECONOMY")));
            return true;
        }

        // Anything that is not a sub-command is a search, so "/orders elytra" works.
        // This also covers Bedrock, where dialog text fields are unreliable.
        if (args.length > 0) {
            int from = args[0].equalsIgnoreCase("search") ? 1 : 0;
            String query = args.length > from
                    ? String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length))
                    : "";
            plugin.sessions().get(player).setQuery(query);
        }

        new OrdersScreen(plugin, player).show();
        return true;
    }

    /**
     * Allowing a spawner works by capturing the real item from an admin's hand, rather
     * than rebuilding it from an entity name. That way the stored template is exactly
     * what HavocSpawners produces, whatever its internal format is.
     */
    private boolean handleSpawners(CommandSender sender, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";

        if (action.equals("status") || action.equals("debug")) {
            sender.sendMessage(Text.component("&#f40d0dSpawner support: &f" + plugin.spawners().status()));
            sender.sendMessage(Text.component("&7Allowed spawners: &f"
                    + plugin.spawnerCatalogue().entries().size()));
            return true;
        }

        if (action.equals("list")) {
            if (plugin.spawnerCatalogue().isEmpty()) {
                sender.sendMessage(Text.component("&7No spawners are orderable yet. "
                        + "Hold one and run &f/orders spawners add <id> [name]"));
                return true;
            }
            sender.sendMessage(Text.component("&#f40d0dOrderable spawners:"));
            plugin.spawnerCatalogue().entries().forEach(entry ->
                    sender.sendMessage(Text.component("&8- &f" + entry.id() + " &7(" + entry.name() + ")")));
            return true;
        }

        if (action.equals("remove")) {
            if (args.length < 3) {
                sender.sendMessage(Text.component("&cUsage: /orders spawners remove <id>"));
                return true;
            }
            boolean removed = plugin.spawnerCatalogue().remove(args[2]);
            sender.sendMessage(Text.component(removed
                    ? "&#f40d0dRemoved &f" + args[2] + "&#f40d0d. Existing orders for it still stand."
                    : "&cNo spawner is allowed under that id."));
            if (removed) plugin.catalogue().build();
            return true;
        }

        if (!action.equals("add")) {
            sender.sendMessage(Text.component("&cUsage: /orders spawners <add|remove|list|status>"));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.component(plugin.message("PLAYERS-ONLY")));
            return true;
        }
        if (!plugin.spawners().isAvailable()) {
            sender.sendMessage(Text.component("&cSpawner support is off: &7" + plugin.spawners().status()));
            return true;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType() == Material.AIR) {
            sender.sendMessage(Text.component("&cHold the spawner you want to allow."));
            return true;
        }
        if (!plugin.spawners().isSpawner(held)) {
            sender.sendMessage(Text.component("&cThat is not a HavocSpawners spawner."));
            return true;
        }
        if (plugin.spawners().hasContents(held)) {
            // The template becomes the canonical item players receive, so it must be clean.
            sender.sendMessage(Text.component("&cThat spawner still holds loot or experience. "
                    + "Empty it first so the template is a clean one."));
            return true;
        }

        String id = args.length > 2 ? args[2] : plugin.spawners().identity(held)
                .toLowerCase(Locale.ROOT).replace('|', '_');
        String name = args.length > 3
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length))
                : plugin.spawners().displayName(held);

        if (!plugin.spawnerCatalogue().add(id, name, held)) {
            sender.sendMessage(Text.component("&cSomething is already allowed under that id."));
            return true;
        }
        plugin.catalogue().build();
        sender.sendMessage(Text.component("&#f40d0dPlayers can now order &f" + name
                + " &#f40d0d(id: " + id.toLowerCase(Locale.ROOT) + ")"));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.add("search");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("spawners")
                && sender.hasPermission("havocorders.admin")) {
            options.addAll(List.of("add", "remove", "list", "status"));
            options.removeIf(option -> !option.startsWith(args[1].toLowerCase(Locale.ROOT)));
            return options;
        }
        if (args.length == 1 && sender.hasPermission("havocorders.admin")) {
            options.add("spawners");
            options.add("reload");
            options.add("import");
            options.removeIf(option -> !option.startsWith(args[0].toLowerCase(Locale.ROOT)));
        }
        return options;
    }
}
