package net.eclipse.havocorders.storage;

import net.eclipse.havocorders.HavocOrders;
import net.eclipse.havocorders.model.Order;
import net.eclipse.havocorders.model.OrderStatus;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Flat JDBC storage that works against SQLite (default) or MySQL.
 * Every public method is blocking - call them from the async executor in OrderManager.
 */
public class SqlStorage {

    private static final String TABLE = "havoc_orders";
    private static final String PROFILE_TABLE = "havoc_profiles";

    private final HavocOrders plugin;
    private final boolean mysql;
    private final String url;
    private final String user;
    private final String password;

    private Connection connection;

    public SqlStorage(HavocOrders plugin) {
        this.plugin = plugin;
        ConfigurationSection db = plugin.getConfig().getConfigurationSection("DATABASE");
        String type = db == null ? "SQLITE" : db.getString("TYPE", "SQLITE");
        this.mysql = "MYSQL".equalsIgnoreCase(type);

        if (mysql) {
            ConfigurationSection my = db.getConfigurationSection("MYSQL");
            String host = my.getString("HOST", "127.0.0.1");
            int port = my.getInt("PORT", 3306);
            String database = my.getString("DATABASE", "havocorders");
            boolean ssl = my.getBoolean("USE-SSL", false);
            this.url = "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=" + ssl + "&allowPublicKeyRetrieval=true&characterEncoding=utf8";
            this.user = my.getString("USER", "");
            this.password = my.getString("PASSWORD", "");
        } else {
            File file = new File(plugin.getDataFolder(), "orders.db");
            this.url = "jdbc:sqlite:" + file.getAbsolutePath();
            this.user = null;
            this.password = null;
        }
    }

    public synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            if (mysql) {
                connection = DriverManager.getConnection(url, user, password);
            } else {
                try {
                    Class.forName("org.sqlite.JDBC");
                } catch (ClassNotFoundException ignored) {
                    // Paper/Purpur ship the driver; if it is missing DriverManager will report it.
                }
                connection = DriverManager.getConnection(url);
            }
        }
        return connection;
    }

    public void initialise() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "id VARCHAR(36) NOT NULL PRIMARY KEY,"
                + "owner VARCHAR(36) NOT NULL,"
                + "owner_name VARCHAR(32),"
                + "item TEXT NOT NULL,"
                + "amount INT NOT NULL,"
                + "unit_price DOUBLE NOT NULL,"
                + "delivered INT NOT NULL,"
                + "collected INT NOT NULL,"
                + "paid DOUBLE NOT NULL,"
                + "created BIGINT NOT NULL,"
                + "expires BIGINT NOT NULL,"
                + "status VARCHAR(16) NOT NULL,"
                + "escrowed BOOLEAN NOT NULL DEFAULT 1,"
                + "pool TEXT"
                + ")";
        try (Statement statement = getConnection().createStatement()) {
            statement.executeUpdate(sql);
            try {
                // For tables created before the escrowed column existed.
                statement.executeUpdate("ALTER TABLE " + TABLE
                        + " ADD COLUMN escrowed BOOLEAN NOT NULL DEFAULT 1");
            } catch (SQLException ignored) {
                // column already present
            }
            try {
                // Holds the real delivered stacks awaiting collection.
                statement.executeUpdate("ALTER TABLE " + TABLE + " ADD COLUMN pool TEXT");
            } catch (SQLException ignored) {
                // column already present
            }
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + PROFILE_TABLE + " ("
                    + "uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
                    + "alerts BOOLEAN NOT NULL"
                    + ")");
            try {
                // MySQL has no "IF NOT EXISTS" for indexes, so this may throw on a second start.
                statement.executeUpdate((mysql ? "CREATE INDEX " : "CREATE INDEX IF NOT EXISTS ")
                        + "idx_havoc_orders_owner ON " + TABLE + " (owner)");
            } catch (SQLException ignored) {
                // index already exists
            }
        }
    }

    public List<Order> loadAll() {
        List<Order> orders = new ArrayList<>();
        String sql = "SELECT * FROM " + TABLE;
        try (PreparedStatement statement = getConnection().prepareStatement(sql);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                try {
                    Order order = new Order(
                            UUID.fromString(results.getString("id")),
                            UUID.fromString(results.getString("owner")),
                            results.getString("owner_name"),
                            results.getString("item"),
                            results.getInt("amount"),
                            results.getDouble("unit_price"),
                            results.getInt("delivered"),
                            results.getInt("collected"),
                            results.getDouble("paid"),
                            results.getLong("created"),
                            results.getLong("expires"),
                            OrderStatus.valueOf(results.getString("status")),
                            results.getBoolean("escrowed")
                    );
                    String pool = results.getString("pool");
                    if (pool != null && !pool.isEmpty()) {
                        order.setPool(new ArrayList<>(List.of(pool.split(";"))));
                    }
                    orders.add(order);
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Skipping malformed order row: " + ex.getMessage());
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load orders", ex);
        }
        return orders;
    }

    public void save(Order order) {
        saveAll(List.of(order));
    }

    public void saveAll(Collection<Order> orders) {
        if (orders.isEmpty()) return;
        String sql = mysql
                ? "REPLACE INTO " + TABLE + " (id,owner,owner_name,item,amount,unit_price,delivered,collected,paid,created,expires,status,escrowed,pool)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                : "INSERT OR REPLACE INTO " + TABLE + " (id,owner,owner_name,item,amount,unit_price,delivered,collected,paid,created,expires,status,escrowed,pool)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try {
            Connection conn = getConnection();
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement statement = conn.prepareStatement(sql)) {
                for (Order order : orders) {
                    statement.setString(1, order.getId().toString());
                    statement.setString(2, order.getOwner().toString());
                    statement.setString(3, order.getOwnerName());
                    statement.setString(4, order.getEncodedItem());
                    statement.setInt(5, order.getAmount());
                    statement.setDouble(6, order.getUnitPrice());
                    statement.setInt(7, order.getDelivered());
                    statement.setInt(8, order.getCollected());
                    statement.setDouble(9, order.getPaid());
                    statement.setLong(10, order.getCreatedAt());
                    statement.setLong(11, order.getExpiresAt());
                    statement.setString(12, order.getStatus().name());
                    statement.setBoolean(13, order.isEscrowed());
                    statement.setString(14, String.join(";", order.getPool()));
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            conn.commit();
            conn.setAutoCommit(previousAutoCommit);
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save orders", ex);
        }
    }

    public void delete(UUID id) {
        try (PreparedStatement statement = getConnection().prepareStatement("DELETE FROM " + TABLE + " WHERE id = ?")) {
            statement.setString(1, id.toString());
            statement.executeUpdate();
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to delete order " + id, ex);
        }
    }

    // ------------------------------------------------------------------ profiles

    public java.util.Map<UUID, Boolean> loadProfiles() {
        java.util.Map<UUID, Boolean> profiles = new java.util.HashMap<>();
        try (PreparedStatement statement =
                     getConnection().prepareStatement("SELECT uuid, alerts FROM " + PROFILE_TABLE);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                try {
                    profiles.put(UUID.fromString(results.getString("uuid")), results.getBoolean("alerts"));
                } catch (IllegalArgumentException ignored) {
                    // malformed uuid
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load player preferences", ex);
        }
        return profiles;
    }

    public void saveProfiles(java.util.Map<UUID, Boolean> profiles) {
        if (profiles.isEmpty()) return;
        String sql = (mysql ? "REPLACE INTO " : "INSERT OR REPLACE INTO ")
                + PROFILE_TABLE + " (uuid, alerts) VALUES (?,?)";
        try {
            Connection conn = getConnection();
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement statement = conn.prepareStatement(sql)) {
                for (java.util.Map.Entry<UUID, Boolean> entry : profiles.entrySet()) {
                    statement.setString(1, entry.getKey().toString());
                    statement.setBoolean(2, entry.getValue());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            conn.commit();
            conn.setAutoCommit(previousAutoCommit);
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save player preferences", ex);
        }
    }

    /** Batched delete used by the periodic flush. */
    public void deleteAll(java.util.Collection<UUID> ids) {
        if (ids.isEmpty()) return;
        try {
            Connection conn = getConnection();
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement statement =
                         conn.prepareStatement("DELETE FROM " + TABLE + " WHERE id = ?")) {
                for (UUID id : ids) {
                    statement.setString(1, id.toString());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            conn.commit();
            conn.setAutoCommit(previousAutoCommit);
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to delete orders", ex);
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException ignored) {
        }
    }
}
