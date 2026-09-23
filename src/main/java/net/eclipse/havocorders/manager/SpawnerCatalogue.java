package net.eclipse.havocorders.manager;

import net.eclipse.havocorders.HavocOrders;
import net.eclipse.havocorders.util.ItemSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * The spawners players are allowed to order.
 *
 * Entries are captured from a real item rather than rebuilt from an entity name, so the
 * template is byte-identical to what HavocSpawners itself hands out - no guessing at its
 * item format, and no drift when that format changes.
 */
public class SpawnerCatalogue {

    public record Allowed(String id, String name, ItemStack template) {
    }

    private final HavocOrders plugin;
    private final Map<String, Allowed> allowed = new LinkedHashMap<>();
    private File file;

    public SpawnerCatalogue(HavocOrders plugin) {
        this.plugin = plugin;
    }

    public List<Allowed> entries() {
        return new ArrayList<>(allowed.values());
    }

    public boolean isEmpty() {
        return allowed.isEmpty();
    }

    public void load() {
        allowed.clear();
        file = new File(plugin.getDataFolder(), "spawners.yml");
        if (!file.exists()) return;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = config.getConfigurationSection("SPAWNERS");
        if (section == null) return;

        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) continue;
            ItemStack template = ItemSerializer.decode(entry.getString("ITEM", ""));
            if (template == null) {
                plugin.getLogger().warning("Spawner entry '" + id + "' has an unreadable item, skipping.");
                continue;
            }
            allowed.put(id.toLowerCase(Locale.ROOT),
                    new Allowed(id.toLowerCase(Locale.ROOT), entry.getString("NAME", id), template));
        }
        if (!allowed.isEmpty()) {
            plugin.getLogger().info(allowed.size() + " spawner(s) are orderable.");
        }
    }

    public boolean add(String id, String name, ItemStack template) {
        String key = id.toLowerCase(Locale.ROOT);
        if (allowed.containsKey(key)) return false;
        allowed.put(key, new Allowed(key, name, template.clone()));
        save();
        return true;
    }

    public boolean remove(String id) {
        if (allowed.remove(id.toLowerCase(Locale.ROOT)) == null) return false;
        save();
        return true;
    }

    /** True when this item is one of the allowed spawners, by identity not raw data. */
    public boolean isAllowed(ItemStack stack) {
        if (!plugin.spawners().isAvailable() || !plugin.spawners().isSpawner(stack)) return false;
        String identity = plugin.spawners().identity(stack);
        for (Allowed entry : allowed.values()) {
            if (plugin.spawners().identity(entry.template()).equals(identity)) return true;
        }
        return false;
    }

    private void save() {
        if (file == null) file = new File(plugin.getDataFolder(), "spawners.yml");
        YamlConfiguration config = new YamlConfiguration();
        for (Allowed entry : allowed.values()) {
            config.set("SPAWNERS." + entry.id() + ".NAME", entry.name());
            config.set("SPAWNERS." + entry.id() + ".ITEM", ItemSerializer.encode(entry.template()));
        }
        try {
            config.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save spawners.yml", ex);
        }
    }
}
