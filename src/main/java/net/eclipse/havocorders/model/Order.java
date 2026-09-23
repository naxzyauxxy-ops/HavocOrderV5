package net.eclipse.havocorders.model;

import net.eclipse.havocorders.util.ItemNames;
import net.eclipse.havocorders.util.ItemSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Order {

    private final UUID id;
    private final UUID owner;
    private String ownerName;

    private final String encodedItem;
    private transient ItemStack cachedItem;

    private final int amount;
    private final double unitPrice;

    private int delivered;
    private int collected;
    private double paid;

    /**
     * The actual stacks that were delivered and not yet collected, base64 encoded.
     *
     * Orders used to hand the owner copies of the template. Once wear is ignored when
     * matching, that would turn a battered elytra into a pristine one on collection -
     * free repairs. Keeping the real items means what was handed in is what comes out.
     */
    private final List<String> pool = new ArrayList<>();

    private final long createdAt;
    private long expiresAt;
    private OrderStatus status;

    /**
     * True when this plugin took the order's value up front and therefore owes a refund
     * on cancel or expiry. Imported orders can be marked false: another plugin already
     * holds that money, and paying it out again would create currency.
     */
    private final boolean escrowed;

    public Order(UUID id, UUID owner, String ownerName, String encodedItem, int amount, double unitPrice,
                 int delivered, int collected, double paid, long createdAt, long expiresAt,
                 OrderStatus status, boolean escrowed) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName;
        this.encodedItem = encodedItem;
        this.amount = amount;
        this.unitPrice = unitPrice;
        this.delivered = delivered;
        this.collected = collected;
        this.paid = paid;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.status = status;
        this.escrowed = escrowed;
    }

    public static Order create(UUID owner, String ownerName, ItemStack item, int amount,
                               double unitPrice, long expiryMillis) {
        long now = System.currentTimeMillis();
        return new Order(UUID.randomUUID(), owner, ownerName, ItemSerializer.encode(item),
                amount, unitPrice, 0, 0, 0.0D, now, now + expiryMillis, OrderStatus.ACTIVE, true);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwner() {
        return owner;
    }

    public String getOwnerName() {
        return ownerName == null ? "Unknown" : ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public String getEncodedItem() {
        return encodedItem;
    }

    /** Single-quantity template stack for this order. Never mutate the returned value. */
    public ItemStack getItem() {
        if (cachedItem == null) {
            ItemStack decoded = ItemSerializer.decode(encodedItem);
            cachedItem = decoded == null ? new ItemStack(Material.BARRIER) : decoded;
        }
        return cachedItem;
    }

    public ItemStack getItemCopy(int stackAmount) {
        ItemStack copy = getItem().clone();
        copy.setAmount(Math.max(1, Math.min(copy.getMaxStackSize(), stackAmount)));
        return copy;
    }

    public Material getMaterial() {
        return getItem().getType();
    }

    public String getItemName() {
        return ItemNames.display(getItem());
    }

    /** Real item type, used for search so a rename cannot masquerade as another item. */
    public String getTypeName() {
        return ItemNames.typeName(getItem());
    }

    public int getAmount() {
        return amount;
    }

    public double getUnitPrice() {
        return unitPrice;
    }

    public double getMaxPaid() {
        return amount * unitPrice;
    }

    public int getDelivered() {
        return delivered;
    }

    /** Records the real stacks handed in, so they can be given out unchanged. */
    public void addDelivered(List<ItemStack> stacks) {
        int quantity = 0;
        for (ItemStack stack : stacks) {
            if (stack == null || stack.getAmount() <= 0) continue;
            pool.add(ItemSerializer.encodeFull(stack));
            quantity += stack.getAmount();
        }
        if (quantity > 0) addDelivered(quantity);
    }

    public List<String> getPool() {
        return pool;
    }

    public void setPool(List<String> encoded) {
        pool.clear();
        if (encoded != null) pool.addAll(encoded);
    }

    /**
     * Removes up to {@code max} items from the pool and returns the real stacks.
     * Falls back to copies of the template for orders that predate the pool, and for
     * anything imported from another plugin.
     */
    public List<ItemStack> takeFromPool(int max) {
        List<ItemStack> taken = new ArrayList<>();
        int remaining = max;

        while (remaining > 0 && !pool.isEmpty()) {
            ItemStack stack = ItemSerializer.decode(pool.get(0));
            if (stack == null) {
                pool.remove(0);
                continue;
            }
            if (stack.getAmount() <= remaining) {
                pool.remove(0);
                taken.add(stack);
                remaining -= stack.getAmount();
            } else {
                ItemStack part = stack.clone();
                part.setAmount(remaining);
                stack.setAmount(stack.getAmount() - remaining);
                pool.set(0, ItemSerializer.encodeFull(stack));
                taken.add(part);
                remaining = 0;
            }
        }

        while (remaining > 0) {
            int size = Math.min(remaining, getItem().getMaxStackSize());
            taken.add(getItemCopy(size));
            remaining -= size;
        }
        return taken;
    }

    public void addDelivered(int quantity) {
        this.delivered += quantity;
        this.paid += quantity * unitPrice;
        if (this.delivered >= amount && status == OrderStatus.ACTIVE) {
            this.status = OrderStatus.COMPLETE;
        }
    }

    public int getRemaining() {
        return Math.max(0, amount - delivered);
    }

    public int getCollected() {
        return collected;
    }

    /** Items delivered but not yet taken (or sold) by the owner. */
    public int getCollectable() {
        return Math.max(0, delivered - collected);
    }

    public void addCollected(int quantity) {
        this.collected += quantity;
    }

    public double getPaid() {
        return paid;
    }

    public boolean isEscrowed() {
        return escrowed;
    }

    public double getRefund() {
        return getRemaining() * unitPrice;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public long getMillisUntilExpiry() {
        return Math.max(0, expiresAt - System.currentTimeMillis());
    }

    public boolean isExpired() {
        return System.currentTimeMillis() >= expiresAt;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    /** Listed publicly, still buying items. */
    public boolean isListed() {
        return status == OrderStatus.ACTIVE && getRemaining() > 0 && !isExpired();
    }

    /** Safe to purge from storage entirely. */
    public boolean isFinished() {
        return status != OrderStatus.ACTIVE && getCollectable() <= 0;
    }

    public boolean matches(ItemStack other) {
        return net.eclipse.havocorders.util.ItemMatching.matches(getItem(), other);
    }
}
