package net.eclipse.havocorders.dialog;

import net.eclipse.havocorders.HavocOrders;
import net.eclipse.havocorders.ui.ScreenModel;
import net.eclipse.havocorders.manager.OrderManager;
import net.eclipse.havocorders.util.NumberUtil;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

public class SellConfirmScreen extends Screen {

    public SellConfirmScreen(HavocOrders plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected String configPath() {
        return "SELL-CONFIRM";
    }

    private Map<String, String> placeholders() {
        OrderManager.SellPreview preview = plugin.orders().previewSell(player);
        return Map.of(
                "amount", NumberUtil.count(preview.sellableItems()),
                "unsellable", NumberUtil.count(preview.unsellableItems()),
                "total", NumberUtil.money(preview.total()));
    }

    @Override
    public String title() {
        return titleFrom(placeholders());
    }

    @Override
    public List<String> bodyLines() {
        return resolve(lines("BODY"), placeholders());
    }

    @Override
    public ScreenModel.Button exitButton() {
        return backButton("BACK", placeholders(), () -> new CollectScreen(plugin, player).show());
    }

    @Override
    public List<ScreenModel.Button> buttons() {
        Map<String, String> placeholders = placeholders();
        return List.of(
                configButton("CONFIRM", placeholders, responses -> {
                    double earned = plugin.orders().sellAll(player);
                    if (earned > 0) success();
                    else deny();
                    new CollectScreen(plugin, player).show();
                })
        );
    }
}
