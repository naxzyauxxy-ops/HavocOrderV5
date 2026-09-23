package net.eclipse.havocorders.command;

import net.eclipse.havocorders.HavocOrders;
import net.eclipse.havocorders.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * /toggleorderalerts - a standalone toggle so external settings menus can bind to it
 * without needing to know anything about this plugin's dialogs.
 */
public class ToggleAlertsCommand implements CommandExecutor {

    private final HavocOrders plugin;

    public ToggleAlertsCommand(HavocOrders plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.component(plugin.message("PLAYERS-ONLY")));
            return true;
        }
        if (!player.hasPermission("havocorders.use")) {
            player.sendMessage(Text.component(plugin.message("NO-PERMISSION")));
            return true;
        }
        boolean enabled = plugin.profiles().toggleAlerts(player.getUniqueId());
        player.sendMessage(Text.component(plugin.message(enabled ? "ALERTS-ON" : "ALERTS-OFF")));
        return true;
    }
}
