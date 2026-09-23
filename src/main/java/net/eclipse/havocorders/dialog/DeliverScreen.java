package net.eclipse.havocorders.dialog;

import net.eclipse.havocorders.HavocOrders;
import net.eclipse.havocorders.ui.ScreenModel;
import net.eclipse.havocorders.manager.InventoryScanner;
import net.eclipse.havocorders.model.Order;
import net.eclipse.havocorders.util.NumberUtil;
import net.eclipse.havocorders.util.Text;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deliver into someone else's order.
 *
 * Shows the order's progress, what the player is carrying, and what a full delivery pays.
 * Alongside the free-text amount field there are one-click quick amounts pulled from
 * config, so the common cases (a stack, a box, a shulker) never need typing.
 */
public class DeliverScreen extends Screen {

    private static final String KEY = "amount";

    private final UUID orderId;

    public DeliverScreen(HavocOrders plugin, Player player, UUID orderId) {
        super(plugin, player);
        this.orderId = orderId;
    }

    @Override
    protected String configPath() {
        return "DELIVER";
    }

    private Order order() {
        return plugin.orders().byId(orderId);
    }

    private int held(Order order) {
        return plugin.orders().countMatching(player, order);
    }

    private int deliverable(Order order) {
        return Math.min(held(order), order.getRemaining());
    }

    private Map<String, String> placeholders(Order order) {
        Map<String, String> map = Placeholders.of(order);
        InventoryScanner.Index index = plugin.inventories().index(player);
        int loose = index.direct(order.getItem());
        int inShulkers = index.nested(order.getItem());
        int held = loose + inShulkers;
        int deliverable = Math.min(held, order.getRemaining());
        map.put("held", NumberUtil.count(held));
        map.put("held_inventory", NumberUtil.count(loose));
        map.put("held_shulkers", NumberUtil.count(inShulkers));
        map.put("deliverable", NumberUtil.count(deliverable));
        map.put("payout", NumberUtil.money(deliverable * order.getUnitPrice()));
        map.put("full_payout", NumberUtil.money(order.getRemaining() * order.getUnitPrice()));
        map.put("balance", NumberUtil.money(plugin.economy().balance(player)));
        return map;
    }

    @Override
    public String title() {
        Order order = order();
        return titleFrom(order == null ? Map.of() : placeholders(order));
    }

    @Override
    public List<String> bodyLines() {
        Order order = order();
        if (order == null) {
            return List.of(plugin.message("ORDER_DELETED"));
        }
        List<String> body = new ArrayList<>();
        body.addAll(resolve(lines("BODY"), placeholders(order)));
        return body;
    }

    @Override
    public List<ScreenModel.Input> inputs() {
        Order order = order();
        if (order == null) return List.of();
        int suggested = Math.max(1, deliverable(order));
        return List.of(new ScreenModel.Input(KEY, string("INPUT-LABEL", "&fAmount"), String.valueOf(suggested)));
    }

    /** Quick-amount buttons, e.g. 64 / 576 / 1728. */
    private List<Integer> quickAmounts() {
        ConfigurationSection section = section();
        if (section == null) return List.of();
        List<Integer> amounts = new ArrayList<>(section.getIntegerList("QUICK-AMOUNTS"));
        amounts.removeIf(amount -> amount == null || amount <= 0);
        return amounts;
    }

    @Override
    public List<ScreenModel.Button> buttons() {
        Order order = order();
        List<ScreenModel.Button> buttons = new ArrayList<>();
        if (order == null) {
            buttons.add(configButton("BACK", Map.of(), responses -> {
                click();
                new OrdersScreen(plugin, player).show();
            }));
            return buttons;
        }

        Map<String, String> placeholders = placeholders(order);

        buttons.add(configButton("DELIVER", placeholders, responses -> {
            Order current = order();
            if (current == null) {
                deny();
                tell(plugin.message("ORDER_DELETED"));
                new OrdersScreen(plugin, player).show();
                return;
            }
            int max = deliverable(current);
            String typed = responses.text(KEY);
            // Bedrock text fields can come back empty through Geyser, so a blank box
            // means "everything I can" rather than an error the player cannot escape.
            Integer amount = typed == null || typed.isBlank()
                    ? max
                    : NumberUtil.parseAmount(typed, max);
            if (amount == null || amount <= 0) {
                deny();
                tell(plugin.message("INVALID-AMOUNT"));
                show();
                return;
            }
            submit(current, amount);
        }));

        buttons.add(configButton("DELIVER-ALL", placeholders, responses -> {
            Order current = order();
            if (current == null) {
                deny();
                new OrdersScreen(plugin, player).show();
                return;
            }
            submit(current, current.getRemaining());
        }));

        int available = deliverable(order);
        for (int amount : quickAmounts()) {
            if (amount > available) continue;
            Map<String, String> quick = new HashMap<>(placeholders);
            quick.put("amount", NumberUtil.count(amount));
            quick.put("value", NumberUtil.money(amount * order.getUnitPrice()));
            buttons.add(configButton("QUICK", quick, responses -> {
                Order current = order();
                if (current == null) {
                    deny();
                    new OrdersScreen(plugin, player).show();
                    return;
                }
                submit(current, amount);
            }));
        }

        return buttons;
    }

    @Override
    public ScreenModel.Button exitButton() {
        Order order = order();
        if (order == null) return null;
        return backButton("BACK", placeholders(order), () -> new OrdersScreen(plugin, player).show());
    }

    private void submit(Order order, int amount) {
        int delivered = plugin.orders().deliver(player, order, amount);
        if (delivered > 0) success();
        else deny();

        Order current = order();
        if (current == null || !current.isListed()) {
            new OrdersScreen(plugin, player).show();
        } else {
            show();
        }
    }

    @Override
    public org.bukkit.inventory.ItemStack bodyIcon() {
        Order order = order();
        return order == null ? null : order.getItemCopy(1);
    }
}
