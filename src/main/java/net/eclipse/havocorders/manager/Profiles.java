package net.eclipse.havocorders.manager;

import net.eclipse.havocorders.HavocOrders;
import net.eclipse.havocorders.storage.SqlStorage;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player preferences. Currently just the "tell me when someone delivers" toggle,
 * which the legacy database also stored, so importing it keeps players' choices intact.
 */
public class Profiles {

    private final HavocOrders plugin;
    private final SqlStorage storage;

    private final Map<UUID, Boolean> alerts = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> dirty = ConcurrentHashMap.newKeySet();

    public Profiles(HavocOrders plugin, SqlStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public void loadAll() {
        alerts.clear();
        alerts.putAll(storage.loadProfiles());
        plugin.getLogger().info("Loaded " + alerts.size() + " player preferences.");
    }

    public boolean alertsEnabled(UUID playerId) {
        return alerts.getOrDefault(playerId, true);
    }

    public void setAlerts(UUID playerId, boolean enabled) {
        alerts.put(playerId, enabled);
        dirty.add(playerId);
    }

    public boolean toggleAlerts(UUID playerId) {
        boolean next = !alertsEnabled(playerId);
        setAlerts(playerId, next);
        return next;
    }

    /** Batched with the order flush; safe from any thread. */
    public void flush() {
        if (dirty.isEmpty()) return;
        java.util.List<UUID> ids = new java.util.ArrayList<>(dirty);
        dirty.removeAll(ids);
        Map<UUID, Boolean> batch = new java.util.HashMap<>();
        for (UUID id : ids) batch.put(id, alertsEnabled(id));
        storage.saveProfiles(batch);
    }
}
