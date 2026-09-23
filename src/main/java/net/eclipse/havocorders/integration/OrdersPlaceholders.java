package net.eclipse.havocorders.integration;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.eclipse.havocorders.HavocOrders;
import net.eclipse.havocorders.util.NumberUtil;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * PlaceholderAPI expansion, so external menus can show and toggle order settings.
 *
 * %havocorders_alerts_status%  ON / OFF, styled by config
 * %havocorders_alerts_raw%     true / false
 * %havocorders_active%         live orders
 * %havocorders_collectable%    items waiting to collect
 * %havocorders_escrow%         money tied up in outstanding orders
 */
public class OrdersPlaceholders extends PlaceholderExpansion {

    private final HavocOrders plugin;

    public OrdersPlaceholders(HavocOrders plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "havocorders";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Eclipse";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        // Survives a PlaceholderAPI reload rather than silently disappearing.
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";
        String key = params.toLowerCase(Locale.ROOT);
        boolean alerts = plugin.profiles().alertsEnabled(player.getUniqueId());

        return switch (key) {
            case "alerts_status", "alerts" -> plugin.statusText(alerts);
            case "alerts_raw" -> String.valueOf(alerts);
            case "active" -> NumberUtil.count(plugin.orders().activeCount(player.getUniqueId()));
            case "collectable" -> NumberUtil.count(plugin.orders().collectableTotal(player.getUniqueId()));
            case "escrow" -> NumberUtil.money(plugin.orders().escrowHeld(player.getUniqueId()));
            default -> null;
        };
    }
}
