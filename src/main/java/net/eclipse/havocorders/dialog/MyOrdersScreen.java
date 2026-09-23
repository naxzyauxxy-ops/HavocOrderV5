package net.eclipse.havocorders.dialog;

import net.eclipse.havocorders.HavocOrders;
import net.eclipse.havocorders.ui.ScreenModel;
import net.eclipse.havocorders.model.Order;
import net.eclipse.havocorders.util.NumberUtil;
import net.eclipse.havocorders.util.Text;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MyOrdersScreen extends Screen {

    /**
     * Order lists are unbounded now, and title/body/buttons/exit each need the list.
     * Computed once per draw instead of four times.
     */
    private List<Order> cached;

    public MyOrdersScreen(HavocOrders plugin, Player player) {
        super(plugin, player);
    }

    @Override
    public void show() {
        cached = null;
        super.show();
    }

    @Override
    protected String configPath() {
        return "MY-ORDERS";
    }

    private int perPage() {
        return Math.max(1, plugin.getConfig().getInt("SETTINGS.ORDERS-PER-PAGE", 8));
    }

    private List<Order> results() {
        if (cached == null) cached = plugin.orders().ordersOf(player.getUniqueId());
        return cached;
    }

    private Map<String, String> screenPlaceholders(List<Order> results) {
        int pages = totalPages(results.size(), perPage());
        Map<String, String> map = new HashMap<>();
        map.put("count", NumberUtil.count(results.size()));
        map.put("collectable", NumberUtil.count(plugin.orders().collectableTotal(player.getUniqueId())));
        map.put("escrow", NumberUtil.money(plugin.orders().escrowHeld(player.getUniqueId())));
        map.put("page", String.valueOf(Math.min(session.getMyOrdersPage() + 1, pages)));
        map.put("pages", String.valueOf(pages));
        map.put("alerts", plugin.profiles().alertsEnabled(player.getUniqueId()) ? "ON" : "OFF");
        return map;
    }

    @Override
    public String title() {
        return titleFrom(screenPlaceholders(results()));
    }

    @Override
    public List<String> bodyLines() {
        List<Order> results = results();
        List<String> body = resolve(lines("BODY"), screenPlaceholders(results));
        if (results.isEmpty()) {
            body.add(string("EMPTY", "&7No orders."));
        }
        return body;
    }

    @Override
    public ScreenModel.Button exitButton() {
        return backButton("BACK", screenPlaceholders(results()),
                () -> new OrdersScreen(plugin, player).show());
    }

    @Override
    public List<ScreenModel.Button> buttons() {
        List<Order> results = results();
        Map<String, String> screen = screenPlaceholders(results);
        int pages = totalPages(results.size(), perPage());
        List<ScreenModel.Button> buttons = new ArrayList<>();

        for (Order order : slice(results, session.getMyOrdersPage(), perPage())) {
            int stackAmount = Math.max(1, Math.min(order.getItem().getMaxStackSize(), order.getRemaining()));
            buttons.add(configButton("ORDER", Placeholders.of(order),
                    order.getItemCopy(stackAmount), responses -> {
                click();
                new ManageOrderScreen(plugin, player, order.getId()).show();
            }));
        }

        if (session.getMyOrdersPage() > 0) {
            buttons.add(configButton("PREVIOUS", screen, responses -> {
                session.setMyOrdersPage(session.getMyOrdersPage() - 1);
                click();
                show();
            }));
        }
        if (session.getMyOrdersPage() < pages - 1) {
            buttons.add(configButton("NEXT", screen, responses -> {
                session.setMyOrdersPage(session.getMyOrdersPage() + 1);
                click();
                show();
            }));
        }

        buttons.add(configButton("NEW-ORDER", screen, responses -> {
            click();
            session.clearDraft();
            new NewOrderScreen(plugin, player).show();
        }));
        buttons.add(configButton("COLLECT", screen, responses -> {
            click();
            new CollectScreen(plugin, player).show();
        }));
        buttons.add(configButton("ALERTS", screen, responses -> {
            boolean enabled = plugin.profiles().toggleAlerts(player.getUniqueId());
            click();
            tell(plugin.message(enabled ? "ALERTS-ON" : "ALERTS-OFF"));
            show();
        }));
        return buttons;
    }
}
