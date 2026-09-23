package net.eclipse.havocorders.model;

import java.util.Comparator;

public enum SortOption {

    MOST_PAID("MOST-PAID", Comparator.comparingDouble(Order::getPaid).reversed()),
    MOST_DELIVERED("MOST-DELIVERED", Comparator.comparingInt(Order::getDelivered).reversed()),
    RECENTLY_LISTED("RECENTLY-LISTED", Comparator.comparingLong(Order::getCreatedAt).reversed()),
    MOST_MONEY_PER_ITEM("MOST-MONEY-PER-ITEM", Comparator.comparingDouble(Order::getUnitPrice).reversed());

    private final String configKey;
    private final Comparator<Order> comparator;

    SortOption(String configKey, Comparator<Order> comparator) {
        this.configKey = configKey;
        this.comparator = comparator;
    }

    public String getConfigKey() {
        return configKey;
    }

    public Comparator<Order> getComparator() {
        return comparator;
    }

    public SortOption next() {
        SortOption[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public SortOption previous() {
        SortOption[] values = values();
        return values[(ordinal() - 1 + values.length) % values.length];
    }
}
