package net.eclipse.havocorders.dialog;

import net.eclipse.havocorders.HavocOrders;
import net.eclipse.havocorders.ui.ScreenModel;
import net.eclipse.havocorders.util.NumberUtil;
import net.eclipse.havocorders.util.Text;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/** Second step for "drop everything", because it is easy to lose loot that way. */
public class DropConfirmScreen extends Screen {

    public DropConfirmScreen(HavocOrders plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected String configPath() {
        return "DROP-CONFIRM";
    }

    private Map<String, String> placeholders() {
        return Map.of("amount",
                NumberUtil.count(plugin.orders().collectableTotal(player.getUniqueId())));
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
                    int dropped = plugin.orders().dropOrders(player,
                            plugin.orders().collectable(player.getUniqueId()));
                    if (dropped > 0) {
                        success();
                        tell(Text.apply(plugin.message("DROPPED-ALL"),
                                Map.of("amount", NumberUtil.count(dropped))));
                    } else {
                        deny();
                    }
                    session.setCollectPage(0);
                    new CollectScreen(plugin, player).show();
                })
        );
    }
}
