package net.eclipse.havocorders.storage;

import net.eclipse.havocorders.HavocOrders;
import net.eclipse.havocorders.model.Order;
import net.eclipse.havocorders.model.OrderStatus;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Imports an orders.db written by the original DonutOrders plugin.
 *
 * Legacy schema:
 *   orders(id VARCHAR(8), deliver, deliverName, unitItemPrice, currentAmount, maxAmount,
 *          collectedAmount, currentPaid, maxPaid, material, serializedItem, createdDate, expireDate)
 *   profiles(uuid, orderAlerts)
 *
 * Two things this deliberately does NOT do:
 *
 *  1. It never pays anyone. Imported orders carry their delivered/paid figures across as
 *     history only. The old plugin already handled that money, and issuing refunds here
 *     would mint currency that was never taken.
 *  2. It never drops uncollected loot. Items owed to owners stay owed and show up in the
 *     collect screen exactly as they did before.
 *
 * Re-running is safe: legacy ids map to a fixed UUID, so an order already imported is
 * skipped rather than duplicated.
 */
public class LegacyImporter {

    public record Report(int orders, int skipped, int failed, int profiles, int itemsOwed) {
    }

    private static final String ID_NAMESPACE = "havocorders:legacy:";

    private final HavocOrders plugin;

    public LegacyImporter(HavocOrders plugin) {
        this.plugin = plugin;
    }

    /** Stable id so importing twice does not duplicate anything. */
    public static UUID idFor(String legacyId) {
        return UUID.nameUUIDFromBytes((ID_NAMESPACE + legacyId).getBytes(StandardCharsets.UTF_8));
    }

    private ConfigurationSection settings() {
        return plugin.getConfig().getConfigurationSection("IMPORT");
    }

    public File defaultFile() {
        ConfigurationSection section = settings();
        String name = section == null ? "import.db" : section.getString("FILE", "import.db");
        File file = new File(name);
        return file.isAbsolute() ? file : new File(plugin.getDataFolder(), name);
    }

    public Report importFrom(File file) throws SQLException {
        ConfigurationSection section = settings();
        String pattern = section == null ? "dd/MM/yyyy HH:mm:ss"
                : section.getString("DATE-FORMAT", "dd/MM/yyyy HH:mm:ss");
        ZoneId zone = zone(section);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern, Locale.ROOT);

        String expiryMode = section == null ? "EXTEND" : section.getString("EXPIRY.MODE", "EXTEND");
        int extendDays = section == null ? 7 : section.getInt("EXPIRY.EXTEND-DAYS", 7);
        boolean importProfiles = section == null || section.getBoolean("IMPORT-PROFILES", true);
        // Whether HavocOrders should consider itself the holder of these orders' escrow.
        boolean escrowHeld = section == null || section.getBoolean("ESCROW-ALREADY-HELD", true);

        int imported = 0;
        int skipped = 0;
        int failed = 0;
        int profiles = 0;
        int itemsOwed = 0;

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath())) {

            try (Statement statement = connection.createStatement();
                 ResultSet results = statement.executeQuery("SELECT * FROM orders")) {

                while (results.next()) {
                    String legacyId = results.getString("id");
                    try {
                        UUID id = idFor(legacyId);
                        if (plugin.orders().byId(id) != null) {
                            skipped++;
                            continue;
                        }

                        UUID owner = UUID.fromString(results.getString("deliver"));
                        String ownerName = results.getString("deliverName");
                        double unitPrice = results.getDouble("unitItemPrice");
                        int amount = results.getInt("maxAmount");
                        int delivered = Math.max(0, results.getInt("currentAmount"));
                        int collected = Math.max(0, results.getInt("collectedAmount"));
                        double paid = results.getDouble("currentPaid");

                        ItemStack item = readItem(results.getString("serializedItem"),
                                results.getString("material"));
                        if (item == null) {
                            plugin.getLogger().warning("Import: no usable item for order "
                                    + legacyId + " (" + results.getString("material") + "), skipping.");
                            failed++;
                            continue;
                        }

                        long created = epoch(results.getString("createdDate"), formatter, zone,
                                System.currentTimeMillis());
                        long expires = epoch(results.getString("expireDate"), formatter, zone,
                                created + TimeUnit.DAYS.toMillis(7));

                        boolean wasExpired = expires <= System.currentTimeMillis();
                        if (wasExpired && "EXTEND".equalsIgnoreCase(expiryMode) && extendDays > 0) {
                            expires = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(extendDays);
                            wasExpired = false;
                        }

                        OrderStatus status;
                        if (delivered >= amount) {
                            status = OrderStatus.COMPLETE;
                        } else if (wasExpired) {
                            // Left EXPIRED rather than ACTIVE so the expiry sweep does not
                            // treat it as newly expired and refund it.
                            status = OrderStatus.EXPIRED;
                        } else {
                            status = OrderStatus.ACTIVE;
                        }

                        Order order = new Order(id, owner, ownerName,
                                net.eclipse.havocorders.util.ItemSerializer.encode(item),
                                amount, unitPrice, delivered, collected, paid,
                                created, expires, status, escrowHeld);

                        if (plugin.orders().importOrder(order)) {
                            imported++;
                            itemsOwed += order.getCollectable();
                        } else {
                            skipped++;
                        }
                    } catch (Exception ex) {
                        failed++;
                        plugin.getLogger().log(Level.WARNING,
                                "Import: could not convert legacy order " + legacyId, ex);
                    }
                }
            }

            if (importProfiles) {
                profiles = importProfiles(connection);
            }
        }

        plugin.orders().flush();
        return new Report(imported, skipped, failed, profiles, itemsOwed);
    }

    private int importProfiles(Connection connection) {
        int count = 0;
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery("SELECT uuid, orderAlerts FROM profiles")) {
            while (results.next()) {
                try {
                    UUID uuid = UUID.fromString(results.getString("uuid"));
                    plugin.profiles().setAlerts(uuid, results.getBoolean("orderAlerts"));
                    count++;
                } catch (IllegalArgumentException ignored) {
                    // malformed uuid, nothing worth keeping
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Import: no profiles table, skipping alerts", ex);
        }
        return count;
    }

    /**
     * The legacy blob is a BukkitObjectOutputStream dump of the ItemStack. If it cannot be
     * read - a removed material, or data written by a newer server - fall back to the
     * plain material column so the order is not lost.
     */
    private ItemStack readItem(String serialized, String materialName) {
        if (serialized != null && !serialized.isEmpty()) {
            try (ByteArrayInputStream bytes = new ByteArrayInputStream(Base64.getDecoder().decode(serialized));
                 BukkitObjectInputStream input = new BukkitObjectInputStream(bytes)) {
                Object read = input.readObject();
                if (read instanceof ItemStack stack && stack.getType() != Material.AIR) {
                    stack.setAmount(1);
                    return stack;
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("Import: item blob unreadable for " + materialName
                        + " (" + ex.getClass().getSimpleName() + "), using the material instead.");
            }
        }
        if (materialName == null) return null;
        Material material = Material.matchMaterial(materialName.toUpperCase(Locale.ROOT));
        return material == null || material == Material.AIR ? null : new ItemStack(material);
    }

    private ZoneId zone(ConfigurationSection section) {
        String name = section == null ? "" : section.getString("TIMEZONE", "");
        if (name == null || name.isEmpty()) return ZoneId.systemDefault();
        try {
            return ZoneId.of(name);
        } catch (Exception ex) {
            plugin.getLogger().warning("Import: unknown TIMEZONE '" + name + "', using system default.");
            return ZoneId.systemDefault();
        }
    }

    private long epoch(String raw, DateTimeFormatter formatter, ZoneId zone, long fallback) {
        if (raw == null || raw.isEmpty()) return fallback;
        try {
            return LocalDateTime.parse(raw, formatter).atZone(zone).toInstant().toEpochMilli();
        } catch (Exception ex) {
            return fallback;
        }
    }

    /** Renames the file so a restart does not import it again. */
    public void markDone(File file) {
        File done = new File(file.getParentFile(), file.getName() + ".imported");
        if (!file.renameTo(done)) {
            plugin.getLogger().warning("Import: could not rename " + file.getName()
                    + ". Move or delete it yourself, or it will be re-read on the next start.");
        }
    }

    public List<String> summary(Report report) {
        List<String> lines = new ArrayList<>();
        lines.add("Imported " + report.orders() + " orders (" + report.skipped()
                + " already present, " + report.failed() + " failed).");
        lines.add(report.itemsOwed() + " items are waiting for their owners to collect.");
        lines.add("Imported " + report.profiles() + " player alert preferences.");
        return lines;
    }
}
