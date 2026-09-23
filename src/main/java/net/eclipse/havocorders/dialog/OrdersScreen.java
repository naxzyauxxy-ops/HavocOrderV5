package net.eclipse.havocorders.dialog;

import net.eclipse.havocorders.HavocOrders;
import net.eclipse.havocorders.ui.ScreenModel;
import net.eclipse.havocorders.model.Order;
import net.eclipse.havocorders.util.Category;
import net.eclipse.havocorders.util.NumberUtil;
import net.eclipse.havocorders.util.Text;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The public order board. */
public class OrdersScreen extends Screen {

    public OrdersScreen(HavocOrders plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected String configPath() {
        return "ORDERS";
    }

    private int perPage() {
        return Math.max(1, plugin.getConfig().getInt("SETTINGS.ORDERS-PER-PAGE", 8));
    }

    /**
     * Returns the filtered, sorted board. Recomputed only when the order set changed or
     * the player altered a filter, otherwise the cached list is reused, so turning a page
     * costs a sublist and nothing else.
     */
    private List<Order> results() {
        long version = plugin.orders().version();
        if (!session.isBoardStale(version)) {
            return session.getCachedBoard();
        }

        List<String> tokens = tokenise(session.getQuery());
        Category filter = session.getFilter();
        List<Order> matched = new ArrayList<>();
        for (Order order : plugin.orders().listed()) {
            if (!filter.matches(order.getMaterial())) continue;
            if (!matches(order, tokens)) continue;
            matched.add(order);
        }
        matched.sort(session.getSort().getComparator());
        session.cacheBoard(matched, version);
        return matched;
    }

    /** Every word must match the real item type or the owner's name. */
    private boolean matches(Order order, List<String> tokens) {
        if (tokens.isEmpty()) return true;
        String haystack = (order.getTypeName() + ' ' + order.getMaterial().name().replace('_', ' ')
                + ' ' + order.getOwnerName()).toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (!haystack.contains(token)) return false;
        }
        return true;
    }

    static List<String> tokenise(String query) {
        if (query == null || query.isBlank()) return List.of();
        List<String> tokens = new ArrayList<>();
        for (String part : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!part.isBlank()) tokens.add(part);
        }
        return tokens;
    }

    private Map<String, String> screenPlaceholders(List<Order> results) {
        int pages = totalPages(results.size(), perPage());
        Map<String, String> map = new java.util.HashMap<>();
        map.put("page", String.valueOf(Math.min(session.getPage() + 1, pages)));
        map.put("pages", String.valueOf(pages));
        map.put("previous", String.valueOf(Math.max(1, session.getPage())));
        map.put("next", String.valueOf(Math.min(session.getPage() + 2, pages)));
        map.put("results", NumberUtil.count(results.size()));
        map.put("sort", plugin.sortName(session.getSort()));
        map.put("filter", plugin.categoryName(session.getFilter()));
        map.put("query", session.getQuery().isEmpty() ? "none" : session.getQuery());
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
            body.add(string("EMPTY", "&7Nothing here."));
        }
        return body;
    }

    @Override
    public ScreenModel.Button exitButton() {
        return closeButton("CLOSE", screenPlaceholders(results()));
    }

    @Override
    public List<ScreenModel.Button> buttons() {
        List<Order> results = results();
        Map<String, String> screen = screenPlaceholders(results);
        int pages = totalPages(results.size(), perPage());
        List<ScreenModel.Button> buttons = new ArrayList<>();

        for (Order order : slice(results, session.getPage(), perPage())) {
            boolean mine = order.getOwner().equals(player.getUniqueId());
            Map<String, String> placeholders = Placeholders.of(order);
            placeholders.put("held", NumberUtil.count(plugin.orders().countMatching(player, order)));

            // Your own orders can't be delivered to, so send them somewhere useful
            // instead of bouncing them off an error.
            int stackAmount = Math.max(1, Math.min(order.getItem().getMaxStackSize(), order.getRemaining()));
            buttons.add(configButton(mine ? "OWN-ORDER" : "ORDER", placeholders,
                    order.getItemCopy(stackAmount), responses -> {
                click();
                if (mine) {
                    new ManageOrderScreen(plugin, player, order.getId()).show();
                } else {
                    new DeliverScreen(plugin, player, order.getId()).show();
                }
            }));
        }

        if (session.getPage() > 0) {
            buttons.add(configButton("PREVIOUS", screen, responses -> {
                session.setPage(session.getPage() - 1);
                click();
                show();
            }));
        }
        if (session.getPage() < pages - 1) {
            buttons.add(configButton("NEXT", screen, responses -> {
                session.setPage(session.getPage() + 1);
                click();
                show();
            }));
        }

        buttons.add(configButton("SORT", screen, responses -> {
            session.setSort(session.getSort().next());
            click();
            show();
        }));
        buttons.add(configButton("FILTER", screen, responses -> {
            session.setFilter(session.getFilter().next());
            click();
            show();
        }));
        buttons.add(configButton("SEARCH", screen, responses -> {
            click();
            new SearchScreen(plugin, player, session.getQuery(), value -> {
                session.setQuery(value);
                new OrdersScreen(plugin, player).show();
            }, () -> new OrdersScreen(plugin, player).show()).show();
        }));
        buttons.add(configButton("MY-ORDERS", screen, responses -> {
            click();
            new MyOrdersScreen(plugin, player).show();
        }));

        return buttons;
    }
}
