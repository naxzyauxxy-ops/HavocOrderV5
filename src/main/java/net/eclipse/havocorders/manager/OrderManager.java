package net.eclipse.havocorders.manager;

import net.eclipse.havocorders.HavocOrders;
import net.eclipse.havocorders.model.Order;
import net.eclipse.havocorders.model.OrderStatus;
import net.eclipse.havocorders.storage.SqlStorage;
import net.eclipse.havocorders.util.ItemNames;
import net.eclipse.havocorders.util.NumberUtil;
import net.eclipse.havocorders.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns every order in memory and is the only place that touches money or inventories.
 *
 * Performance notes:
 *  - Nothing here scans the database at runtime; the whole order set lives in memory.
 *  - Writes go into a dirty set and are flushed in one batched, async transaction,
 *    so a busy server does not spawn a thread per delivery.
 *  - {@link #version()} bumps on every mutation. Dialog sessions use it to know when
 *    their cached, filtered view needs rebuilding, so paging costs a list slice.
 */
public class OrderManager {

    private final HavocOrders plugin;
    private final SqlStorage storage;

    private final Map<UUID, Order> orders = new ConcurrentHashMap<>();
    /** Owner -> their orders, kept in sync so "my orders" never scans everything. */
    private final Map<UUID, Set<UUID>> byOwner = new ConcurrentHashMap<>();

    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingDeletes = ConcurrentHashMap.newKeySet();

    private final AtomicLong version = new AtomicLong();

    /** Collapses a burst of changes into one write instead of one per action. */
    private final java.util.concurrent.atomic.AtomicBoolean flushQueued =
            new java.util.concurrent.atomic.AtomicBoolean();

    public OrderManager(HavocOrders plugin, SqlStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public long version() {
        return version.get();
    }

    private void touch() {
        version.incrementAndGet();
    }

    // ------------------------------------------------------------------ loading / saving

    public void loadAll() {
        orders.clear();
        byOwner.clear();
        for (Order order : storage.loadAll()) {
            if (order.isFinished()) {
                pendingDeletes.add(order.getId());
                continue;
            }
            index(order);
        }
        touch();
        plugin.getLogger().info("Loaded " + orders.size() + " orders.");
    }

    /**
     * Adds an order that already exists elsewhere (a migration), without charging anyone.
     * Returns false if an order with that id is already loaded.
     */
    public boolean importOrder(Order order) {
        if (orders.containsKey(order.getId())) return false;
        index(order);
        dirty.add(order.getId());
        touch();
        return true;
    }

    private void index(Order order) {
        orders.put(order.getId(), order);
        byOwner.computeIfAbsent(order.getOwner(), key -> ConcurrentHashMap.newKeySet()).add(order.getId());
    }

    private void unindex(Order order) {
        orders.remove(order.getId());
        Set<UUID> owned = byOwner.get(order.getOwner());
        if (owned != null) {
            owned.remove(order.getId());
            if (owned.isEmpty()) byOwner.remove(order.getOwner());
        }
    }

    /** Called on a timer (async) and once on shutdown. Safe to call from any thread. */
    /**
     * Writes pending changes. Synchronized because a scheduled flush and an immediate
     * one can otherwise interleave batches on a single JDBC connection.
     */
    public synchronized void flush() {
        if (!dirty.isEmpty()) {
            List<UUID> ids = new ArrayList<>(dirty);
            dirty.removeAll(ids);
            List<Order> batch = new ArrayList<>(ids.size());
            for (UUID id : ids) {
                Order order = orders.get(id);
                if (order != null) batch.add(order);
            }
            if (!batch.isEmpty()) storage.saveAll(batch);
        }
        if (!pendingDeletes.isEmpty()) {
            List<UUID> ids = new ArrayList<>(pendingDeletes);
            pendingDeletes.removeAll(ids);
            storage.deleteAll(ids);
        }
    }

    /**
     * Asks for a write as soon as possible.
     *
     * Anything that hands a player an item or moves money must not sit in memory waiting
     * for the periodic flush: if the server dies before that flush, the player keeps the
     * item and the record comes back on restart, which is a duplication bug. Bursts are
     * collapsed so a busy server still writes in batches.
     */
    private void requestFlush() {
        if (flushQueued.compareAndSet(false, true)) {
            plugin.async(() -> {
                flushQueued.set(false);
                flush();
            });
        }
    }

    /** Marks the order for the next flush, or removes it once it is fully settled. */
    private void persistOrRemove(Order order) {
        if (order.isFinished()) {
            unindex(order);
            dirty.remove(order.getId());
            pendingDeletes.add(order.getId());
        } else {
            dirty.add(order.getId());
        }
        touch();
        requestFlush();
    }

    // ------------------------------------------------------------------ lookups

    public Order byId(UUID id) {
        return orders.get(id);
    }

    public List<Order> listed() {
        List<Order> result = new ArrayList<>(orders.size());
        for (Order order : orders.values()) {
            if (order.isListed()) result.add(order);
        }
        return result;
    }

    public List<Order> ordersOf(UUID owner) {
        Set<UUID> owned = byOwner.get(owner);
        if (owned == null || owned.isEmpty()) return List.of();
        List<Order> result = new ArrayList<>(owned.size());
        for (UUID id : owned) {
            Order order = orders.get(id);
            if (order != null && !order.isFinished()) result.add(order);
        }
        result.sort(Comparator.comparingLong(Order::getCreatedAt));
        return result;
    }

    public long activeCount(UUID owner) {
        long count = 0;
        for (Order order : ordersOf(owner)) {
            if (order.isListed()) count++;
        }
        return count;
    }

    public List<Order> collectable(UUID owner) {
        List<Order> result = new ArrayList<>();
        for (Order order : ordersOf(owner)) {
            if (order.getCollectable() > 0) result.add(order);
        }
        return result;
    }

    public int collectableTotal(UUID owner) {
        int total = 0;
        for (Order order : ordersOf(owner)) total += order.getCollectable();
        return total;
    }

    /** Money currently locked up in this player's outstanding orders. */
    public double escrowHeld(UUID owner) {
        double total = 0;
        for (Order order : ordersOf(owner)) {
            if (order.getStatus() == OrderStatus.ACTIVE) total += order.getRefund();
        }
        return total;
    }

    // ------------------------------------------------------------------ creation

    public record Result(boolean success, String message) {
        public static Result ok(String message) {
            return new Result(true, message);
        }

        public static Result fail(String message) {
            return new Result(false, message);
        }
    }

    public Result createOrder(Player player, ItemStack template, int amount, double unitPrice) {
        if (template == null || template.getType() == Material.AIR) {
            return Result.fail(plugin.message("NO-ITEM-SELECTED"));
        }
        // SPAWNER is blacklisted as a raw item, but a spawner an admin explicitly allowed
        // is a different thing, so it is exempt.
        if (plugin.isBlocked(template.getType()) && !plugin.spawnerCatalogue().isAllowed(template)) {
            return Result.fail(plugin.message("BLOCKED-ITEM"));
        }

        int minAmount = plugin.getConfig().getInt("SETTINGS.MIN-ITEM-AMOUNT", 1);
        int maxAmount = plugin.getConfig().getInt("SETTINGS.MAX-ITEM-AMOUNT", 3456);
        double minPrice = plugin.getConfig().getDouble("SETTINGS.MIN-PRICE-AMOUNT", 1.0D);
        double maxPrice = plugin.getConfig().getDouble("SETTINGS.MAX-PRICE-AMOUNT", 1_000_000D);

        if (amount < minAmount) {
            return Result.fail(Text.apply(plugin.message("AMOUNT-TOO-LOW"),
                    Map.of("min", NumberUtil.count(minAmount))));
        }
        if (amount > maxAmount) {
            return Result.fail(Text.apply(plugin.message("AMOUNT-TOO-HIGH"),
                    Map.of("max", NumberUtil.count(maxAmount))));
        }
        if (unitPrice < minPrice) {
            return Result.fail(Text.apply(plugin.message("PRICE-TOO-LOW"),
                    Map.of("min", NumberUtil.money(minPrice))));
        }
        if (unitPrice > maxPrice) {
            return Result.fail(Text.apply(plugin.message("PRICE-TOO-HIGH"),
                    Map.of("max", NumberUtil.money(maxPrice))));
        }

        // 0 means unlimited.
        int maxOrders = plugin.getConfig().getInt("SETTINGS.MAX-ORDERS-PER-PLAYER", 0);
        if (maxOrders > 0 && !player.hasPermission("havocorders.admin")
                && activeCount(player.getUniqueId()) >= maxOrders) {
            return Result.fail(Text.apply(plugin.message("MAX_ORDERS_REACHED"),
                    Map.of("max", String.valueOf(maxOrders))));
        }

        double total = (double) amount * unitPrice;
        double maxValue = plugin.getConfig().getDouble("SETTINGS.MAX-ORDER-VALUE", 0D);
        if (maxValue > 0 && total > maxValue) {
            return Result.fail(Text.apply(plugin.message("ORDER-TOO-EXPENSIVE"),
                    Map.of("total", NumberUtil.money(total), "max", NumberUtil.money(maxValue))));
        }

        boolean escrow = plugin.getConfig().getBoolean("SETTINGS.ESCROW", true);
        if (escrow) {
            if (!plugin.economy().has(player, total)) {
                return Result.fail(Text.apply(plugin.message("NOT-ENOUGH-MONEY"),
                        Map.of("total", NumberUtil.money(total))));
            }
            if (!plugin.economy().withdraw(player, total)) {
                return Result.fail(plugin.message("REFUND-ERROR"));
            }
        }

        long expiry = TimeUnit.DAYS.toMillis(plugin.getConfig().getInt("SETTINGS.EXPIRE-DAYS", 7));
        Order order = Order.create(player.getUniqueId(), player.getName(), template, amount, unitPrice, expiry);
        index(order);
        dirty.add(order.getId());
        touch();
        requestFlush();

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("amount", NumberUtil.count(amount));
        placeholders.put("item", ItemNames.display(template));
        placeholders.put("total", NumberUtil.money(total));
        return Result.ok(Text.apply(plugin.message("ORDERED"), placeholders));
    }

    // ------------------------------------------------------------------ delivering

    /** Loose stacks plus anything inside carried shulker boxes. Cached briefly. */
    public int countMatching(Player player, Order order) {
        return plugin.inventories().index(player).total(order.getItem());
    }

    /** Split of the same count, for the deliver screen. */
    public InventoryScanner.Index inventoryIndex(Player player) {
        return plugin.inventories().index(player);
    }

    /** Delivers up to {@code requested} matching items. Returns how many actually moved. */
    public int deliver(Player player, Order order, int requested) {
        Order current = orders.get(order.getId());
        if (current == null) {
            player.sendMessage(Text.component(plugin.message("ORDER_DELETED")));
            return 0;
        }
        if (current.getOwner().equals(player.getUniqueId())) {
            player.sendMessage(Text.component(plugin.message("OWN-ORDER")));
            return 0;
        }
        if (!current.getStatus().acceptsDeliveries() || current.isExpired()) {
            player.sendMessage(Text.component(plugin.message("ORDER_NO_LONGER_VALID")));
            return 0;
        }
        if (current.getRemaining() <= 0) {
            player.sendMessage(Text.component(plugin.message("ORDER_FULL")));
            return 0;
        }

        // Always re-scan before taking items; the display cache may be a second stale.
        int available = plugin.inventories().countExact(player, current.getItem());
        int deliverable = Math.min(Math.min(requested, current.getRemaining()), available);
        if (deliverable <= 0) {
            player.sendMessage(Text.component(plugin.message("NOTHING-TO-DELIVER")));
            return 0;
        }

        // Trust what was actually removed rather than what was expected.
        List<ItemStack> handedOver =
                plugin.inventories().removeAndCollect(player, current.getItem(), deliverable);
        deliverable = handedOver.stream().mapToInt(ItemStack::getAmount).sum();
        if (deliverable <= 0) {
            player.sendMessage(Text.component(plugin.message("NOTHING-TO-DELIVER")));
            return 0;
        }

        double payout = deliverable * current.getUnitPrice();
        current.addDelivered(handedOver);
        persistOrRemove(current);

        if (!plugin.getConfig().getBoolean("SETTINGS.ESCROW", true)) {
            plugin.economy().withdraw(Bukkit.getOfflinePlayer(current.getOwner()), payout);
        }
        plugin.economy().deposit(player, payout);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("amount", NumberUtil.count(deliverable));
        placeholders.put("item", current.getItemName());
        placeholders.put("received", NumberUtil.money(payout));
        placeholders.put("deliverer", player.getName());
        player.sendMessage(Text.component(Text.apply(plugin.message("DELIVERED"), placeholders)));

        Player owner = Bukkit.getPlayer(current.getOwner());
        if (owner != null && owner.isOnline() && plugin.profiles().alertsEnabled(current.getOwner())) {
            owner.sendMessage(Text.component(Text.apply(plugin.message("DELIVERY_RECEIVED"), placeholders)));
        }
        return deliverable;
    }

    // ------------------------------------------------------------------ collecting

    public int collect(Player player, Order order, int requested) {
        if (!order.getOwner().equals(player.getUniqueId())) return 0;
        int available = Math.min(requested, order.getCollectable());
        if (available <= 0) {
            player.sendMessage(Text.component(plugin.message("COLLECT.NOTHING_TO_COLLECT")));
            return 0;
        }

        int given = giveStacks(player, order.takeFromPool(available));
        if (given <= 0) {
            player.sendMessage(Text.component(plugin.message("COLLECT.INVENTORY_FULL")));
            return 0;
        }
        order.addCollected(given);
        persistOrRemove(order);

        Map<String, String> placeholders = Map.of(
                "amount", NumberUtil.count(given),
                "item", order.getItemName(),
                "collected", NumberUtil.count(given),
                "total", NumberUtil.count(available));
        player.sendMessage(Text.component(Text.apply(
                plugin.message(given < available ? "COLLECT.PARTIAL_COLLECTION" : "COLLECT.SUCCESS"), placeholders)));
        return given;
    }

    public int collectAll(Player player) {
        int total = 0;
        for (Order order : collectable(player.getUniqueId())) {
            int given = giveStacks(player, order.takeFromPool(order.getCollectable()));
            if (given <= 0) continue;
            order.addCollected(given);
            persistOrRemove(order);
            total += given;
        }
        if (total == 0) {
            player.sendMessage(Text.component(plugin.message("COLLECT.NOTHING_TO_COLLECT")));
        }
        return total;
    }

    /** Hands over real stacks and reports how many items actually fitted. */
    private int giveStacks(Player player, List<ItemStack> stacks) {
        int given = 0;
        for (ItemStack stack : stacks) {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            int notGiven = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
            given += stack.getAmount() - notGiven;
            for (ItemStack left : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), left);
                given += left.getAmount();
            }
        }
        if (given > 0) plugin.inventories().invalidate(player);
        return given;
    }

    private int give(Player player, ItemStack template, int quantity) {
        int given = 0;
        int remaining = quantity;
        while (remaining > 0) {
            int stackSize = Math.min(remaining, template.getMaxStackSize());
            ItemStack stack = template.clone();
            stack.setAmount(stackSize);
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            int notGiven = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
            given += stackSize - notGiven;
            if (notGiven > 0) break;
            remaining -= stackSize;
        }
        if (given > 0) plugin.inventories().invalidate(player);
        return given;
    }

    // ------------------------------------------------------------------ dropping

    /**
     * Drops the loot from the given orders at the player's feet.
     *
     * The stacks are queued and released a few per tick rather than all at once, so
     * "drop all" on a hundred thousand items cannot freeze the server. Book-keeping is
     * done immediately, so the items are never owed twice even if the player logs off.
     */
    public int dropOrders(Player player, List<Order> subset) {
        int perTick = Math.max(1, plugin.getConfig().getInt("SETTINGS.DROP.MAX-STACKS-PER-TICK", 24));
        int maxStacks = plugin.getConfig().getInt("SETTINGS.DROP.MAX-TOTAL-STACKS", 0);

        Deque<DropJob> jobs = new ArrayDeque<>();
        int totalItems = 0;
        int totalStacks = 0;
        boolean capped = false;

        for (Order order : subset) {
            int quantity = order.getCollectable();
            if (quantity <= 0) continue;

            ItemStack template = order.getItem();
            if (maxStacks > 0) {
                int stackSize = Math.max(1, template.getMaxStackSize());
                int allowance = (maxStacks - totalStacks) * stackSize;
                if (allowance <= 0) {
                    capped = true;
                    break;
                }
                if (quantity > allowance) {
                    quantity = allowance;
                    capped = true;
                }
            }

            DropJob job = new DropJob(template, quantity);
            jobs.add(job);
            totalStacks += job.stackCount();
            totalItems += quantity;

            // Debited straight away so the items can never be handed out twice,
            // even if the player logs off part-way through the drop.
            order.addCollected(quantity);
            persistOrRemove(order);

            if (capped) break;
        }

        if (jobs.isEmpty()) {
            player.sendMessage(Text.component(plugin.message("COLLECT.NOTHING_TO_COLLECT")));
            return 0;
        }

        if (capped) {
            player.sendMessage(Text.component(Text.apply(plugin.message("DROP-CAPPED"),
                    Map.of("amount", NumberUtil.count(totalStacks)))));
        }
        plugin.spreadDrop(player, jobs, perTick);
        return totalItems;
    }

    // ------------------------------------------------------------------ selling

    public record SellPreview(int sellableItems, int unsellableItems, double total) {
    }

    public SellPreview previewSell(Player player) {
        int sellable = 0;
        int unsellable = 0;
        double total = 0.0D;
        for (Order order : collectable(player.getUniqueId())) {
            int quantity = order.getCollectable();
            double unit = plugin.sellPrices().unitPrice(order.getItem());
            if (unit <= 0) {
                unsellable += quantity;
            } else {
                sellable += quantity;
                total += unit * quantity;
            }
        }
        return new SellPreview(sellable, unsellable, total);
    }

    public double sellAll(Player player) {
        if (!plugin.sellPrices().isEnabled()) {
            player.sendMessage(Text.component(plugin.message("SELL-DISABLED")));
            return 0.0D;
        }

        double total = 0.0D;
        int sold = 0;
        for (Order order : collectable(player.getUniqueId())) {
            int quantity = order.getCollectable();
            double unit = plugin.sellPrices().unitPrice(order.getItem());
            if (unit <= 0 || quantity <= 0) continue;
            total += unit * quantity;
            sold += quantity;
            order.addCollected(quantity);
            persistOrRemove(order);
        }

        if (sold == 0) {
            player.sendMessage(Text.component(plugin.message("SELL-NOTHING")));
            return 0.0D;
        }

        plugin.economy().deposit(player, total);
        player.sendMessage(Text.component(Text.apply(plugin.message("SELL_ALL_SOLD"),
                Map.of("total", NumberUtil.money(total), "amount", NumberUtil.count(sold)))));
        return total;
    }

    // ------------------------------------------------------------------ cancel / expiry

    public Result cancel(Player player, Order order) {
        Order current = orders.get(order.getId());
        if (current == null) return Result.fail(plugin.message("ORDER_DELETED"));
        if (!current.getOwner().equals(player.getUniqueId()) && !player.hasPermission("havocorders.admin")) {
            return Result.fail(plugin.message("NO-PERMISSION"));
        }
        if (current.getCollectable() > 0) {
            return Result.fail(Text.apply(plugin.message("CANCEL-NOT-ALLOWED-PENDING-COLLECTION"),
                    Map.of("amount", NumberUtil.count(current.getCollectable()))));
        }

        double refund = current.getRefund();
        current.setStatus(OrderStatus.CANCELLED);

        if (current.isEscrowed() && plugin.getConfig().getBoolean("SETTINGS.ESCROW", true) && refund > 0) {
            if (!plugin.economy().deposit(player, refund)) {
                current.setStatus(OrderStatus.ACTIVE);
                return Result.fail(plugin.message("REFUND-ERROR"));
            }
            player.sendMessage(Text.component(Text.apply(plugin.message("REFUND-ISSUED"),
                    Map.of("refund", NumberUtil.money(refund)))));
        } else if (refund <= 0) {
            player.sendMessage(Text.component(plugin.message("NO-REFUND")));
        }

        persistOrRemove(current);
        return Result.ok(plugin.message("ORDER-CANCELLED"));
    }

    /**
     * Timer task. Walks the order set once a minute; with a few thousand orders this is
     * a handful of microseconds, and it exits early when nothing has expired.
     */
    public void tickExpiry() {
        long now = System.currentTimeMillis();
        for (Order order : orders.values()) {
            if (order.getStatus() == OrderStatus.ACTIVE && now >= order.getExpiresAt()) {
                double refund = order.getRefund();
                order.setStatus(OrderStatus.EXPIRED);
                if (order.isEscrowed() && plugin.getConfig().getBoolean("SETTINGS.ESCROW", true)
                        && refund > 0) {
                    plugin.economy().deposit(Bukkit.getOfflinePlayer(order.getOwner()), refund);
                }
                Player owner = Bukkit.getPlayer(order.getOwner());
                if (owner != null) {
                    owner.sendMessage(Text.component(Text.apply(plugin.message("EXPIRED"),
                            Map.of("amount", NumberUtil.count(order.getRemaining()),
                                    "item", order.getItemName()))));
                }
                persistOrRemove(order);
            } else if (order.isFinished()) {
                persistOrRemove(order);
            }
        }
    }

    /** Used by the shutdown path. */
    public List<Order> snapshot() {
        return new ArrayList<>(orders.values());
    }
}
