package net.eclipse.havocorders;

import net.eclipse.havocorders.command.OrdersCommand;
import net.eclipse.havocorders.command.ToggleAlertsCommand;
import net.eclipse.havocorders.integration.OrdersPlaceholders;
import net.eclipse.havocorders.economy.EconomyHook;
import net.eclipse.havocorders.economy.SellPrices;
import net.eclipse.havocorders.manager.DropJob;
import net.eclipse.havocorders.manager.InventoryScanner;
import net.eclipse.havocorders.integration.SpawnerSupport;
import net.eclipse.havocorders.manager.ItemCatalogue;
import net.eclipse.havocorders.manager.SpawnerCatalogue;
import net.eclipse.havocorders.manager.OrderManager;
import net.eclipse.havocorders.manager.Profiles;
import net.eclipse.havocorders.manager.SessionManager;
import net.eclipse.havocorders.model.SortOption;
import net.eclipse.havocorders.storage.LegacyImporter;
import net.eclipse.havocorders.storage.SqlStorage;
import net.eclipse.havocorders.util.Category;
import net.eclipse.havocorders.util.ConfigUpdater;
import net.eclipse.havocorders.ui.Gui;
import net.eclipse.havocorders.ui.GuiListener;
import net.eclipse.havocorders.ui.Prompts;
import net.eclipse.havocorders.util.ItemMatching;
import net.eclipse.havocorders.util.NumberUtil;
import net.eclipse.havocorders.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.sql.SQLException;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

public final class HavocOrders extends JavaPlugin {

    private static HavocOrders inst;

    private FileConfiguration menus;

    private EconomyHook econ;
    private SellPrices sellPrices;
    private SqlStorage storage;
    private OrderManager orders;
    private ItemCatalogue catalogue;
    private SessionManager sessions;
    private InventoryScanner inventories;
    private Profiles profiles;
    private LegacyImporter importer;
    private SpawnerSupport spawners;
    private SpawnerCatalogue spawnerCatalogue;
    private Gui gui;
    private Prompts prompts;

    private final Set<Material> blocked = new HashSet<>();

    public static HavocOrders get() {
        return inst;
    }

    @Override
    public void onEnable() {
        inst = this;

        // resources
        saveDefaultConfig();
        syncConfigFiles();
        reloadMenus();
        loadBlockedItems();
        NumberUtil.setAbbreviate(getConfig().getBoolean("SETTINGS.ABBREVIATE-NUMBERS", true));

        // economy. don't disable if it's missing - EssentialsX and friends register their
        // vault service in their own onEnable, so we might just be first in the load order
        this.econ = new EconomyHook(this);
        if (!econ.setup()) {
            getLogger().warning("No Vault economy registered yet - waiting for one.");
            waitForEconomy();
        }
        this.sellPrices = new SellPrices(this);

        // storage
        this.storage = new SqlStorage(this);
        try {
            storage.initialise();
        } catch (SQLException ex) {
            getLogger().log(Level.SEVERE, "Could not initialise the database - disabling.", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // core
        this.orders   = new OrderManager(this, storage);
        this.profiles = new Profiles(this, storage);
        this.importer = new LegacyImporter(this);
        orders.loadAll();
        profiles.loadAll();
        runAutoImport();

        // spawners have to be hooked before the catalogue builds or they miss the picker
        this.spawners = new SpawnerSupport(this);
        spawners.hook();
        ItemMatching.setSpawnerSupport(spawners);
        applyMatchingRules();

        this.spawnerCatalogue = new SpawnerCatalogue(this);
        this.catalogue        = new ItemCatalogue(this);
        this.inventories      = new InventoryScanner(this);
        spawnerCatalogue.load();
        catalogue.build();

        sessions = new SessionManager();
        getServer().getPluginManager().registerEvents(sessions, this);

        prompts = new Prompts(this);
        getServer().getPluginManager().registerEvents(prompts, this);
        gui = new Gui(this);
        getServer().getPluginManager().registerEvents(new GuiListener(this), this);

        PluginCommand command = getCommand("orders");
        if (command != null) {
            OrdersCommand executor = new OrdersCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        PluginCommand toggle = getCommand("toggleorderalerts");
        if (toggle != null) toggle.setExecutor(new ToggleAlertsCommand(this));

        registerPlaceholders();

        long expiryTicks = Math.max(20L, getConfig().getInt("SETTINGS.EXPIRY-CHECK-SECONDS", 60) * 20L);
        getServer().getScheduler().runTaskTimer(this, () -> orders.tickExpiry(), expiryTicks, expiryTicks);

        // Single batched writer. Nothing else touches the database at runtime.
        long saveTicks = Math.max(20L, getConfig().getInt("SETTINGS.SAVE-INTERVAL-SECONDS", 30) * 20L);
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            orders.flush();
            profiles.flush();
        }, saveTicks, saveTicks);

        watchConfigFiles();

        getLogger().info("HavocOrders enabled. Dialogs require Paper/Purpur 1.21.7+ and a 1.21.6+ client.");
    }

    /** PlaceholderAPI is optional; skip quietly when it is not installed. */
    private void registerPlaceholders() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) return;
        try {
            new OrdersPlaceholders(this).register();
            getLogger().info("Registered PlaceholderAPI expansion 'havocorders'.");
        } catch (Throwable ex) {
            getLogger().warning("Could not register placeholders: " + ex.getMessage());
        }
    }

    /** ON/OFF text used by the placeholders, styled in config. */
    public String statusText(boolean enabled) {
        return getConfig().getString("PLACEHOLDERS." + (enabled ? "ENABLED-TEXT" : "DISABLED-TEXT"),
                enabled ? "ON" : "OFF");
    }

    /** Picks up a legacy orders.db dropped into the plugin folder. */
    private void runAutoImport() {
        if (!getConfig().getBoolean("IMPORT.ENABLED", true)) return;
        File file = importer.defaultFile();
        if (!file.isFile()) return;

        getLogger().info("Found " + file.getName() + ", importing legacy orders...");
        try {
            LegacyImporter.Report report = importer.importFrom(file);
            for (String line : importer.summary(report)) getLogger().info(line);
            if (getConfig().getBoolean("IMPORT.RENAME-WHEN-DONE", true)) {
                importer.markDone(file);
            }
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Legacy import failed; nothing was changed.", ex);
        }
    }

    /** Retries the Vault hook until a provider shows up, then stops looking. */
    private void waitForEconomy() {
        new BukkitRunnable() {
            private int attempts = 0;

            @Override
            public void run() {
                if (econ.isReady()) {
                    cancel();
                    return;
                }
                if (econ.setup()) {
                    getLogger().info("Vault economy found - fully enabled.");
                    cancel();
                    return;
                }
                if (++attempts >= 60) {
                    getLogger().severe("Still no Vault economy after a minute. "
                            + "Install one, then run the reload command.");
                    cancel();
                }
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    /**
     * Reloads config and menus when the files change on disk, so edits apply without a
     * restart or a manual reload.
     */
    private void watchConfigFiles() {
        int seconds = getConfig().getInt("SETTINGS.RELOAD-WATCH-SECONDS", 5);
        if (seconds <= 0) return;

        File configFile = new File(getDataFolder(), "config.yml");
        File dialogFile = new File(getDataFolder(), "menus.yml");
        long[] stamps = {configFile.lastModified(), dialogFile.lastModified()};

        getServer().getScheduler().runTaskTimer(this, () -> {
            long config = configFile.lastModified();
            long dialog = dialogFile.lastModified();
            if (config == stamps[0] && dialog == stamps[1]) return;
            stamps[0] = config;
            stamps[1] = dialog;
            reloadEverything();
            getLogger().info("Config changed on disk - reloaded.");
        }, seconds * 20L, seconds * 20L);
    }

    @Override
    public void onDisable() {
        if (profiles != null) profiles.flush();
        if (orders != null) {
            orders.flush();
            storage.saveAll(orders.snapshot());
        }
        if (storage != null) storage.close();
    }

    // ------------------------------------------------------------------ config

    /**
     * Brings config.yml and menus.yml up to date with this build before anything reads
     * them, so a jar update never needs settings pasted in by hand.
     */
    private void syncConfigFiles() {
        if (!getConfig().getBoolean("SETTINGS.AUTO-UPDATE-CONFIG", true)) return;

        ConfigUpdater.Result config = ConfigUpdater.update(this, "config.yml");
        if (config.changed()) reloadConfig();
        ConfigUpdater.report(this, config);

        ConfigUpdater.Result menus = ConfigUpdater.update(this, "menus.yml");
        ConfigUpdater.report(this, menus);
    }

    public void reloadMenus() {
        File file = new File(getDataFolder(), "menus.yml");
        if (!file.exists()) saveResource("menus.yml", false);
        menus = YamlConfiguration.loadConfiguration(file);
    }

    private void loadBlockedItems() {
        blocked.clear();
        for (String raw : getConfig().getStringList("ITEM-RESTRICTIONS.BLOCKED-ITEMS")) {
            Material material = Material.matchMaterial(raw.toUpperCase(Locale.ROOT));
            if (material == null) {
                getLogger().warning("Unknown blocked item: " + raw);
                continue;
            }
            blocked.add(material);
        }
    }

    public void reloadEverything() {
        reloadConfig();
        reloadMenus();
        loadBlockedItems();
        NumberUtil.setAbbreviate(getConfig().getBoolean("SETTINGS.ABBREVIATE-NUMBERS", true));
        sellPrices.reload();
        spawners.hook();
        applyMatchingRules();
        spawnerCatalogue.load();
        catalogue.build();
    }

    private void applyMatchingRules() {
        ItemMatching.configure(
                "AT-LEAST".equalsIgnoreCase(getConfig().getString("SETTINGS.MATCHING.ENCHANTMENTS", "AT-LEAST")),
                getConfig().getBoolean("SETTINGS.MATCHING.IGNORE-DAMAGE", true),
                getConfig().getInt("SETTINGS.MATCHING.MIN-DURABILITY-PERCENT", 0));
    }

    public boolean isBlocked(Material material) {
        return material == null || material == Material.AIR || blocked.contains(material);
    }

    public ConfigurationSection menuSection(String path) {
        return menus.getConfigurationSection("MENUS." + path);
    }

    /** Shared menu values such as the border item. */
    public String menuString(String path, String fallback) {
        return menus.getString(path, fallback);
    }

    public String sortName(SortOption option) {
        return menus.getString("NAMES.SORT." + option.getConfigKey(), Text.pretty(option.name()));
    }

    public String categoryName(Category category) {
        return menus.getString("NAMES.FILTER." + category.name(), Text.pretty(category.name()));
    }

    /** Reusable line templates from menus.yml LINES. */
    public String line(String key, String fallback) {
        return menus.getString("LINES." + key, fallback);
    }

    public String message(String path) {
        String prefix = getConfig().getString("MESSAGES.PREFIX", "");
        String message = getConfig().getString("MESSAGES." + path, "");
        return message.isEmpty() ? "" : prefix + message;
    }

    // ------------------------------------------------------------------ accessors

    public EconomyHook economy() {
        return econ;
    }

    public SellPrices sellPrices() {
        return sellPrices;
    }

    public OrderManager orders() {
        return orders;
    }

    public ItemCatalogue catalogue() {
        return catalogue;
    }

    public SpawnerSupport spawners() {
        return spawners;
    }

    public SpawnerCatalogue spawnerCatalogue() {
        return spawnerCatalogue;
    }

    public SessionManager sessions() {
        return sessions;
    }

    public Gui gui() {
        return gui;
    }

    public Prompts prompts() {
        return prompts;
    }


    public InventoryScanner inventories() {
        return inventories;
    }

    public Profiles profiles() {
        return profiles;
    }

    public LegacyImporter importer() {
        return importer;
    }

    // ------------------------------------------------------------------ scheduling

    public void sync(Runnable runnable) {
        if (!isEnabled()) return;
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            getServer().getScheduler().runTask(this, runnable);
        }
    }

    public void async(Runnable runnable) {
        if (!isEnabled()) {
            runnable.run();
            return;
        }
        getServer().getScheduler().runTaskAsynchronously(this, runnable);
    }

    /**
     * Releases queued loot a few stacks per tick at the player's feet.
     *
     * Stacks are cut from the job as they are dropped rather than pre-built, so dropping
     * a million items holds one template and a counter instead of thousands of stacks.
     * Dropping everything in one tick is the classic way to stall a server; this doesn't.
     */
    public void spreadDrop(Player player, Deque<DropJob> jobs, int perTick) {
        Location fallback = player.getLocation();
        new BukkitRunnable() {
            @Override
            public void run() {
                if (jobs.isEmpty()) {
                    cancel();
                    return;
                }

                boolean online = player.isOnline();
                Location target = online ? player.getLocation() : fallback;
                // An offline player's remaining loot lands where they were standing,
                // rather than being silently lost.
                int budget = online ? perTick : Integer.MAX_VALUE;

                for (int index = 0; index < budget && !jobs.isEmpty(); index++) {
                    DropJob job = jobs.peek();
                    ItemStack stack = job.nextStack();
                    if (stack == null) {
                        jobs.poll();
                        index--;
                        continue;
                    }
                    target.getWorld().dropItemNaturally(target, stack);
                }

                if (jobs.isEmpty()) cancel();
            }
        }.runTaskTimer(this, 1L, 1L);
    }
}
