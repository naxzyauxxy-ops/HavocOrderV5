package net.eclipse.havocorders.dialog;

import net.eclipse.havocorders.HavocOrders;
import net.eclipse.havocorders.ui.ScreenModel;
import net.eclipse.havocorders.manager.OrderManager;
import net.eclipse.havocorders.util.ItemNames;
import net.eclipse.havocorders.util.NumberUtil;
import net.eclipse.havocorders.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The order builder. Amount and price are free-text so shorthand like 1k / 2.5m works;
 * whatever is typed is captured from the response view before navigating away, so the
 * draft survives a trip to the item picker.
 */
public class NewOrderScreen extends Screen {

    private static final String AMOUNT = "amount";
    private static final String PRICE = "price";

    public NewOrderScreen(HavocOrders plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected String configPath() {
        return "NEW-ORDER";
    }

    /**
     * Pulls the typed values into the session so they are not lost on navigation.
     * A blank or unreadable field keeps the previous value, which is what makes this
     * survive Geyser handing back empty text on Bedrock.
     */
    private void capture(ScreenModel.Responses responses) {
        Integer amount = NumberUtil.parseAmount(responses.text(AMOUNT), session.getDraftAmount());
        if (amount != null && amount > 0) session.setDraftAmount(amount);

        Double price = NumberUtil.parse(responses.text(PRICE));
        if (price != null && price > 0) session.setDraftPrice(price);
    }

    private Map<String, String> placeholders() {
        Map<String, String> map = new HashMap<>();
        ItemStack draft = session.getDraftItem();
        map.put("material", draft == null ? "none" : ItemNames.display(draft));
        map.put("amount", NumberUtil.count(session.getDraftAmount()));
        map.put("price", NumberUtil.money(session.getDraftPrice()));
        map.put("total", NumberUtil.money(session.getDraftTotal()));
        map.put("balance", NumberUtil.money(plugin.economy().balance(player)));

        StringBuilder enchants = new StringBuilder();
        for (java.util.Map.Entry<org.bukkit.enchantments.Enchantment, Integer> entry
                : EnchantEditScreen.current(draft).entrySet()) {
            if (enchants.length() > 0) enchants.append("&8, ");
            enchants.append("&f").append(net.eclipse.havocorders.util.ItemNames.enchantment(entry.getKey()))
                    .append(' ').append(Text.roman(entry.getValue()));
        }
        map.put("enchants", enchants.length() == 0 ? "&7none" : enchants.toString());
        return map;
    }

    @Override
    public String title() {
        return titleFrom(placeholders());
    }

    @Override
    public List<String> bodyLines() {
        List<String> body = new ArrayList<>();
        ItemStack draft = session.getDraftItem();
        if (draft != null) body.addAll(resolve(lines("BODY"), placeholders()));
        return body;
    }

    @Override
    public List<ScreenModel.Input> inputs() {
        return List.of(
                new ScreenModel.Input(AMOUNT, string("AMOUNT-LABEL", "&fAmount"), String.valueOf(session.getDraftAmount())),
                new ScreenModel.Input(PRICE, string("PRICE-LABEL", "&fPrice each"), NumberUtil.exact(session.getDraftPrice()))
        );
    }

    @Override
    public ScreenModel.Button exitButton() {
        // Note: the footer button cannot capture typed input, so the draft keeps
        // whatever was last confirmed on one of the grid buttons.
        return backButton("BACK", placeholders(), () -> new MyOrdersScreen(plugin, player).show());
    }

    @Override
    public List<ScreenModel.Button> buttons() {
        Map<String, String> placeholders = placeholders();
        List<ScreenModel.Button> buttons = new ArrayList<>();

        buttons.add(configButton("CHOOSE-ITEM", placeholders, responses -> {
            capture(responses);
            click();
            new ItemPickerScreen(plugin, player).show();
        }));

        if (session.getDraftItem() != null) {
            buttons.add(configButton("ENCHANTS", placeholders, responses -> {
                capture(responses);
                click();
                session.setEnchantPage(0);
                new EnchantEditScreen(plugin, player).show();
            }));
        }

        buttons.add(configButton("HELD-ITEM", placeholders, responses -> {
            capture(responses);
            ItemStack held = player.getInventory().getItemInMainHand();
            if (held == null || held.getType() == Material.AIR) {
                deny();
                tell(plugin.message("NO-ITEM-SELECTED"));
            } else if (plugin.isBlocked(held.getType())) {
                deny();
                tell(plugin.message("BLOCKED-ITEM"));
            } else {
                session.setDraftItem(held);
                success();
            }
            show();
        }));

        buttons.add(configButton("CONFIRM", placeholders, responses -> {
            capture(responses);
            OrderManager.Result result = plugin.orders().createOrder(
                    player, session.getDraftItem(), session.getDraftAmount(), session.getDraftPrice());
            tell(result.message());
            if (result.success()) {
                success();
                session.clearDraft();
                new MyOrdersScreen(plugin, player).show();
            } else {
                deny();
                show();
            }
        }));

        return buttons;
    }

    @Override
    public org.bukkit.inventory.ItemStack bodyIcon() {
        return session.getDraftItem() == null ? null : session.getDraftItem().clone();
    }
}
