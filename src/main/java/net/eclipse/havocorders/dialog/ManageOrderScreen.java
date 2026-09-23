package net.eclipse.havocorders.dialog;

import net.eclipse.havocorders.HavocOrders;
import net.eclipse.havocorders.ui.ScreenModel;
import net.eclipse.havocorders.model.Order;
import net.eclipse.havocorders.util.Text;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ManageOrderScreen extends Screen {

    private final UUID orderId;

    public ManageOrderScreen(HavocOrders plugin, Player player, UUID orderId) {
        super(plugin, player);
        this.orderId = orderId;
    }

    @Override
    protected String configPath() {
        return "MANAGE-ORDER";
    }

    private Order order() {
        return plugin.orders().byId(orderId);
    }

    @Override
    public String title() {
        Order order = order();
        return titleFrom(order == null ? Map.of() : Placeholders.of(order));
    }

    @Override
    public List<String> bodyLines() {
        Order order = order();
        if (order == null) {
            return List.of(plugin.message("ORDER_DELETED"));
        }
        List<String> body = new ArrayList<>();
        body.addAll(resolve(lines("BODY"), Placeholders.of(order)));
        return body;
    }

    @Override
    public ScreenModel.Button exitButton() {
        Order order = order();
        return backButton("BACK", order == null ? Map.of() : Placeholders.of(order),
                () -> new MyOrdersScreen(plugin, player).show());
    }

    @Override
    public List<ScreenModel.Button> buttons() {
        Order order = order();
        List<ScreenModel.Button> buttons = new ArrayList<>();
        Map<String, String> placeholders = order == null ? Map.of() : Placeholders.of(order);

        if (order != null) {
            if (order.getCollectable() > 0) {
                buttons.add(configButton("COLLECT", placeholders, responses -> {
                    click();
                    new CollectScreen(plugin, player).show();
                }));
            }
            buttons.add(configButton("CANCEL-ORDER", placeholders, responses -> {
                click();
                new CancelConfirmScreen(plugin, player, orderId).show();
            }));
        }

        return buttons;
    }

    @Override
    public org.bukkit.inventory.ItemStack bodyIcon() {
        Order order = order();
        return order == null ? null : order.getItemCopy(1);
    }
}
