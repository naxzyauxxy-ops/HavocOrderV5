package net.eclipse.havocorders.economy;

import net.eclipse.havocorders.HavocOrders;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.Map;

/**
 * Sell values used by the "Sell All" button.
 * Prices are per single item and read from config.yml -> SELL.PRICES.
 */
public class SellPrices {

    private final HavocOrders plugin;
    private final Map<Material, Double> prices = new EnumMap<>(Material.class);

    private boolean enabled = true;
    private double defaultPrice = 0.0D;
    private double multiplier = 1.0D;

    public SellPrices(HavocOrders plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        prices.clear();
        ConfigurationSection sell = plugin.getConfig().getConfigurationSection("SELL");
        if (sell == null) return;

        enabled = sell.getBoolean("ENABLED", true);
        defaultPrice = sell.getDouble("DEFAULT-PRICE", 0.0D);
        multiplier = sell.getDouble("MULTIPLIER", 1.0D);

        ConfigurationSection section = sell.getConfigurationSection("PRICES");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            if (material == null) {
                plugin.getLogger().warning("Unknown material in SELL.PRICES: " + key);
                continue;
            }
            prices.put(material, section.getDouble(key));
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Sell value of one item. */
    public double unitPrice(ItemStack item) {
        if (item == null || !enabled) return 0.0D;
        double base = prices.getOrDefault(item.getType(), defaultPrice);
        return Math.max(0.0D, base * multiplier);
    }

    public double totalPrice(ItemStack item, int quantity) {
        return unitPrice(item) * quantity;
    }

    public boolean isSellable(ItemStack item) {
        return unitPrice(item) > 0.0D;
    }
}
