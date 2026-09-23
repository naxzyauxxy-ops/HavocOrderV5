package net.eclipse.havocorders.dialog;

import net.eclipse.havocorders.model.Order;
import net.eclipse.havocorders.util.NumberUtil;
import net.eclipse.havocorders.util.TimeUtil;

import java.util.HashMap;
import java.util.Map;

public final class Placeholders {

    private Placeholders() {
    }

    /** ▰▰▰▱▱▱ style progress bar for an order's completion. */
    public static String bar(int current, int max, int length) {
        if (max <= 0) return "";
        int filled = (int) Math.round((Math.min(current, max) / (double) max) * length);
        StringBuilder sb = new StringBuilder();
        for (int index = 0; index < length; index++) {
            sb.append(index < filled ? "&#f40d0d\u25b0" : "&8\u25b0");
        }
        return sb.toString();
    }

    public static Map<String, String> of(Order order) {
        Map<String, String> map = new HashMap<>();
        map.put("player", order.getOwnerName());
        map.put("material", order.getItemName());
        map.put("unit_price", NumberUtil.money(order.getUnitPrice()));
        map.put("current", NumberUtil.count(order.getDelivered()));
        map.put("max", NumberUtil.count(order.getAmount()));
        map.put("amount_left", NumberUtil.count(order.getRemaining()));
        map.put("remaining", NumberUtil.count(order.getRemaining()));
        map.put("paid", NumberUtil.money(order.getPaid()));
        map.put("max_paid", NumberUtil.money(order.getMaxPaid()));
        map.put("refund", NumberUtil.money(order.getRefund()));
        map.put("collectable", NumberUtil.count(order.getCollectable()));
        map.put("expires", TimeUtil.shortDuration(order.getMillisUntilExpiry()));
        int percent = order.getAmount() <= 0 ? 0
                : (int) Math.floor(order.getDelivered() * 100.0D / order.getAmount());
        map.put("percent", String.valueOf(percent));
        map.put("progress", bar(order.getDelivered(), order.getAmount(), 20));
        return map;
    }
}
