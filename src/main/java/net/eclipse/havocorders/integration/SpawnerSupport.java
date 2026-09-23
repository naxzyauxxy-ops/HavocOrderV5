package net.eclipse.havocorders.integration;

import net.eclipse.havocorders.HavocOrders;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.logging.Level;

/**
 * Optional bridge to HavocSpawners.
 *
 * Everything is reflective, so this plugin builds and runs with or without the spawners
 * plugin present; if it is missing, spawner orders simply never appear.
 *
 * The reason this exists rather than relying on ItemStack#isSimilar: a Havoc spawner
 * carries its stored loot, stored experience, upgrade level and stack size in its item
 * data. Two zombie spawners that are the same thing to a player are different items to
 * isSimilar the moment one has a mob's worth of drops inside it. Orders therefore match
 * on the spawner's identity - entity type, item material, and optionally level - and
 * ignore the parts that drift.
 */
public class SpawnerSupport {

    private final HavocOrders plugin;

    private boolean available;
    private String status = "not checked yet";
    private long lastAttempt;
    private Object items;
    private Method isHavocSpawner;
    private Method readEntityType;
    private Method readItemMaterial;
    private Method readLevel;
    private Method readStackSize;
    private Method readStorage;
    private Method readStoredExp;

    public SpawnerSupport(HavocOrders plugin) {
        this.plugin = plugin;
    }

    /**
     * Re-hooks on demand if the first attempt failed.
     *
     * Plugin load order is not guaranteed, so HavocSpawners may enable after this plugin
     * and a single hook at startup would miss it forever. Retries are throttled because
     * this is called on every item match.
     */
    public boolean isAvailable() {
        if (!available && System.currentTimeMillis() - lastAttempt > 10_000L) {
            hook();
        }
        return available;
    }

    /** Human-readable reason, for the admin command. */
    public String status() {
        return status;
    }

    public void hook() {
        available = false;
        lastAttempt = System.currentTimeMillis();

        if (!plugin.getConfig().getBoolean("SPAWNERS.ENABLED", true)) {
            status = "disabled in config (SPAWNERS.ENABLED)";
            return;
        }

        Plugin spawners = Bukkit.getPluginManager().getPlugin("HavocSpawners");
        if (spawners == null) {
            status = "HavocSpawners is not installed";
            return;
        }
        if (!spawners.isEnabled()) {
            status = "HavocSpawners is installed but not enabled yet";
            return;
        }

        try {
            items = spawners.getClass().getMethod("items").invoke(spawners);
            if (items == null) return;

            Class<?> type = items.getClass();
            isHavocSpawner = type.getMethod("isHavocSpawner", ItemStack.class);
            readEntityType = type.getMethod("readEntityType", ItemStack.class);
            readItemMaterial = type.getMethod("readItemMaterial", ItemStack.class);
            readLevel = type.getMethod("readLevel", ItemStack.class);
            readStackSize = type.getMethod("readStackSize", ItemStack.class);
            readStorage = type.getMethod("readStorage", ItemStack.class);
            readStoredExp = type.getMethod("readStoredExp", ItemStack.class);

            available = true;
            status = "hooked into HavocSpawners";
            plugin.getLogger().info("Hooked into HavocSpawners - spawner orders enabled.");
        } catch (Throwable ex) {
            status = "HavocSpawners API did not match: " + ex;
            plugin.getLogger().log(Level.WARNING,
                    "HavocSpawners is present but its API did not match; spawner orders are off.", ex);
            available = false;
        }
    }

    public boolean isSpawner(ItemStack stack) {
        if (!available || stack == null || stack.getType() == Material.AIR) return false;
        try {
            return (boolean) isHavocSpawner.invoke(items, stack);
        } catch (Exception ex) {
            return false;
        }
    }

    private Object read(Method method, ItemStack stack) {
        try {
            return method.invoke(items, stack);
        } catch (Exception ex) {
            return null;
        }
    }

    public String entityType(ItemStack stack) {
        Object value = read(readEntityType, stack);
        return value == null ? null : value.toString();
    }

    public Material itemMaterial(ItemStack stack) {
        Object value = read(readItemMaterial, stack);
        return value instanceof Material material ? material : null;
    }

    public int level(ItemStack stack) {
        Object value = read(readLevel, stack);
        return value instanceof Integer integer ? integer : 1;
    }

    public int stackSize(ItemStack stack) {
        Object value = read(readStackSize, stack);
        return value instanceof Integer integer ? Math.max(1, integer) : 1;
    }

    /** True when the spawner still holds loot or experience someone would lose. */
    public boolean hasContents(ItemStack stack) {
        Object storage = read(readStorage, stack);
        if (storage instanceof Map<?, ?> map && !map.isEmpty()) return true;
        Object exp = read(readStoredExp, stack);
        return exp instanceof Long stored && stored > 0L;
    }

    /**
     * What makes two spawners "the same order". Deliberately excludes stored loot,
     * experience and stack size.
     */
    public String identity(ItemStack stack) {
        String entity = entityType(stack);
        Material material = itemMaterial(stack);
        StringBuilder sb = new StringBuilder();
        sb.append(entity == null ? "none" : entity);
        sb.append('|').append(material == null ? "none" : material.name());
        if (plugin.getConfig().getBoolean("SPAWNERS.MATCH-LEVEL", true)) {
            sb.append("|lvl").append(level(stack));
        }
        return sb.toString();
    }

    /** Whether a carried spawner may be handed over for an order of this template. */
    public boolean deliverable(ItemStack template, ItemStack candidate) {
        if (!isSpawner(template) || !isSpawner(candidate)) return false;
        if (!identity(template).equals(identity(candidate))) return false;

        if (plugin.getConfig().getBoolean("SPAWNERS.REQUIRE-EMPTY-STORAGE", true)
                && hasContents(candidate)) {
            // Orders hand the buyer a clean copy, so a full spawner would lose its
            // contents on delivery. Better to refuse it than to quietly delete loot.
            return false;
        }
        return !plugin.getConfig().getBoolean("SPAWNERS.REQUIRE-SINGLE", true)
                || stackSize(candidate) <= 1;
    }

    /** Readable name for a spawner item, e.g. "Zombie Spawner" or "Diamond Spawner". */
    public String displayName(ItemStack stack) {
        Material material = itemMaterial(stack);
        if (material != null) {
            return net.eclipse.havocorders.util.Text.pretty(material.name()) + " Spawner";
        }
        String entity = entityType(stack);
        return entity == null
                ? "Spawner"
                : net.eclipse.havocorders.util.Text.pretty(entity) + " Spawner";
    }
}
