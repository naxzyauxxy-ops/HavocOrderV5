package net.eclipse.havocorders.dialog;

import net.eclipse.havocorders.HavocOrders;
import net.eclipse.havocorders.ui.ScreenModel;
import net.eclipse.havocorders.manager.ItemCatalogue;
import net.eclipse.havocorders.util.NumberUtil;
import net.eclipse.havocorders.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ItemPickerScreen extends Screen {

    public ItemPickerScreen(HavocOrders plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected String configPath() {
        return "ITEM-PICKER";
    }

    private int perPage() {
        return Math.max(1, plugin.getConfig().getInt("SETTINGS.ITEMS-PER-PAGE", 12));
    }

    private List<ItemCatalogue.Entry> results() {
        return plugin.catalogue().search(session.getItemFilter(),
                session.getItemQuery().toLowerCase(Locale.ROOT));
    }

    private Map<String, String> screenPlaceholders(List<ItemCatalogue.Entry> results) {
        int pages = totalPages(results.size(), perPage());
        Map<String, String> map = new HashMap<>();
        map.put("page", String.valueOf(Math.min(session.getItemPage() + 1, pages)));
        map.put("pages", String.valueOf(pages));
        map.put("results", NumberUtil.count(results.size()));
        map.put("filter", plugin.categoryName(session.getItemFilter()));
        map.put("query", session.getItemQuery().isEmpty() ? "none" : session.getItemQuery());
        return map;
    }

    @Override
    public String title() {
        return titleFrom(screenPlaceholders(results()));
    }

    @Override
    public List<String> bodyLines() {
        List<ItemCatalogue.Entry> results = results();
        List<String> body = resolve(lines("BODY"), screenPlaceholders(results));
        if (results.isEmpty()) {
            body.add(string("EMPTY", "&7No matches."));
        }
        return body;
    }

    @Override
    public ScreenModel.Button exitButton() {
        return backButton("BACK", screenPlaceholders(results()),
                () -> new NewOrderScreen(plugin, player).show());
    }

    @Override
    public List<ScreenModel.Button> buttons() {
        List<ItemCatalogue.Entry> results = results();
        Map<String, String> screen = screenPlaceholders(results);
        int pages = totalPages(results.size(), perPage());
        List<ScreenModel.Button> buttons = new ArrayList<>();

        for (ItemCatalogue.Entry entry : slice(results, session.getItemPage(), perPage())) {
            Map<String, String> placeholders = new HashMap<>(screen);
            placeholders.put("name", entry.name());
            buttons.add(configButton("ITEM", placeholders, entry.stack().clone(), responses -> {
                if (entry.stack().getType() == Material.ENCHANTED_BOOK) {
                    click();
                    new EnchantPickerScreen(plugin, player).show();
                    return;
                }
                session.setDraftItem(entry.stack());
                success();
                new NewOrderScreen(plugin, player).show();
            }));
        }

        if (session.getItemPage() > 0) {
            buttons.add(configButton("PREVIOUS", screen, responses -> {
                session.setItemPage(session.getItemPage() - 1);
                click();
                show();
            }));
        }
        if (session.getItemPage() < pages - 1) {
            buttons.add(configButton("NEXT", screen, responses -> {
                session.setItemPage(session.getItemPage() + 1);
                click();
                show();
            }));
        }

        buttons.add(configButton("FILTER", screen, responses -> {
            session.setItemFilter(session.getItemFilter().next());
            click();
            show();
        }));
        buttons.add(configButton("SEARCH", screen, responses -> {
            click();
            new SearchScreen(plugin, player, session.getItemQuery(), value -> {
                session.setItemQuery(value);
                new ItemPickerScreen(plugin, player).show();
            }, () -> new ItemPickerScreen(plugin, player).show()).show();
        }));
        return buttons;
    }
}
