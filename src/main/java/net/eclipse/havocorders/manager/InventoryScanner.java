package net.eclipse.havocorders.manager;

import net.eclipse.havocorders.HavocOrders;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts and removes items across a player's inventory, including the contents of any
 * shulker boxes they are carrying.
 *
 * Reading a shulker's contents means deserialising its block state, which is expensive.
 * The order board calls "how many do you have" once per visible order, so a naive
 * implementation would deserialise every carried shulker twenty-odd times per screen.
 * Instead one scan builds a count of everything the player holds, and that snapshot is
 * cached for a short window. Anything that actually moves items invalidates it, and
 * deliveries always re-scan before touching the inventory.
 */
public class InventoryScanner {

    /** Snapshot of what a player is carrying, keyed by single-quantity template. */
    public static final class Index {

        private final Map<ItemStack, Integer> direct;
        private final Map<ItemStack, Integer> nested;
        private final long builtAt;

        Index(Map<ItemStack, Integer> direct, Map<ItemStack, Integer> nested, long builtAt) {
            this.direct = direct;
            this.nested = nested;
            this.builtAt = builtAt;
        }

        /** Loose in the inventory. */
        public int direct(ItemStack template) {
            return count(direct, template);
        }

        /**
         * Counts by the order's matching rule rather than exact item data, so a worn
         * elytra is counted towards an order for an elytra.
         */
        private int count(Map<ItemStack, Integer> source, ItemStack template) {
            Integer exact = source.get(key(template));
            int total = exact == null ? 0 : exact;
            for (Map.Entry<ItemStack, Integer> entry : source.entrySet()) {
                if (entry.getKey().equals(key(template))) continue;
                if (net.eclipse.havocorders.util.ItemMatching.matches(template, entry.getKey())) {
                    total += entry.getValue();
                }
            }
            return total;
        }

        /** Inside shulker boxes. */
        public int nested(ItemStack template) {
            return count(nested, template);
        }

        public int total(ItemStack template) {
            return direct(template) + nested(template);
        }

        long builtAt() {
            return builtAt;
        }
    }

    private final HavocOrders plugin;
    private final Map<UUID, Index> cache = new ConcurrentHashMap<>();

    public InventoryScanner(HavocOrders plugin) {
        this.plugin = plugin;
    }

    private static ItemStack key(ItemStack stack) {
        ItemStack copy = stack.clone();
        copy.setAmount(1);
        return copy;
    }

    private boolean shulkersEnabled() {
        return plugin.getConfig().getBoolean("SETTINGS.SHULKERS.DELIVER-FROM-SHULKERS", true);
    }

    private static boolean isShulker(ItemStack stack) {
        return stack != null && stack.getType().name().endsWith("SHULKER_BOX");
    }

    // ------------------------------------------------------------------ scanning

    public Index index(Player player) {
        long ttl = Math.max(0L, plugin.getConfig().getLong("SETTINGS.SHULKERS.CACHE-MILLIS", 1000L));
        Index cached = cache.get(player.getUniqueId());
        if (cached != null && System.currentTimeMillis() - cached.builtAt() < ttl) {
            return cached;
        }
        Index fresh = scan(player);
        cache.put(player.getUniqueId(), fresh);
        return fresh;
    }

    public void invalidate(Player player) {
        cache.remove(player.getUniqueId());
    }

    public void invalidate(UUID playerId) {
        cache.remove(playerId);
    }

    private Index scan(Player player) {
        Map<ItemStack, Integer> direct = new HashMap<>();
        Map<ItemStack, Integer> nested = new HashMap<>();
        boolean readShulkers = shulkersEnabled();

        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType() == Material.AIR) continue;
            direct.merge(key(stack), stack.getAmount(), Integer::sum);

            if (!readShulkers || !isShulker(stack)) continue;
            ItemMeta meta = stack.getItemMeta();
            if (!(meta instanceof BlockStateMeta blockStateMeta)) continue;
            if (!(blockStateMeta.getBlockState() instanceof ShulkerBox box)) continue;

            for (ItemStack inner : box.getInventory().getContents()) {
                if (inner == null || inner.getType() == Material.AIR) continue;
                nested.merge(key(inner), inner.getAmount(), Integer::sum);
            }
        }
        return new Index(direct, nested, System.currentTimeMillis());
    }

    /** Fresh count, ignoring the cache. Used right before items are taken. */
    public int countExact(Player player, ItemStack template) {
        invalidate(player);
        return index(player).total(template);
    }

    // ------------------------------------------------------------------ removal

    /**
     * Removes up to {@code amount} matching items, loose stacks first and shulker
     * contents afterwards, so players keep their boxes packed where possible.
     * Returns how many were actually taken.
     */
    public int remove(Player player, ItemStack template, int amount) {
        return removeAndCollect(player, template, amount).stream()
                .mapToInt(ItemStack::getAmount).sum();
    }

    /**
     * Removes up to {@code amount} matching items and returns the actual stacks taken,
     * so the caller can pass on exactly what the player handed over rather than a copy
     * of some template.
     */
    public java.util.List<ItemStack> removeAndCollect(Player player, ItemStack template, int amount) {
        java.util.List<ItemStack> taken = new java.util.ArrayList<>();
        if (amount <= 0) return taken;
        int remaining = amount;

        ItemStack[] contents = player.getInventory().getStorageContents();

        // Pass one: loose stacks.
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || !net.eclipse.havocorders.util.ItemMatching.matches(template, stack)) continue;
            int take = Math.min(stack.getAmount(), remaining);
            ItemStack copy = stack.clone();
            copy.setAmount(take);
            taken.add(copy);
            stack.setAmount(stack.getAmount() - take);
            if (stack.getAmount() <= 0) contents[slot] = null;
            remaining -= take;
        }

        // Pass two: inside shulkers.
        if (remaining > 0 && shulkersEnabled()) {
            for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
                ItemStack stack = contents[slot];
                if (!isShulker(stack)) continue;
                ItemMeta meta = stack.getItemMeta();
                if (!(meta instanceof BlockStateMeta blockStateMeta)) continue;
                if (!(blockStateMeta.getBlockState() instanceof ShulkerBox box)) continue;

                ItemStack[] inner = box.getInventory().getContents();
                boolean changed = false;
                for (int index = 0; index < inner.length && remaining > 0; index++) {
                    ItemStack candidate = inner[index];
                    if (candidate == null
                            || !net.eclipse.havocorders.util.ItemMatching.matches(template, candidate)) continue;
                    int take = Math.min(candidate.getAmount(), remaining);
                    ItemStack copy = candidate.clone();
                    copy.setAmount(take);
                    taken.add(copy);
                    candidate.setAmount(candidate.getAmount() - take);
                    if (candidate.getAmount() <= 0) inner[index] = null;
                    remaining -= take;
                    changed = true;
                }

                if (changed) {
                    box.getInventory().setContents(inner);
                    blockStateMeta.setBlockState(box);
                    stack.setItemMeta(blockStateMeta);
                }
            }
        }

        player.getInventory().setStorageContents(contents);
        invalidate(player);
        return taken;
    }
}
