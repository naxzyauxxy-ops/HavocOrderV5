package net.eclipse.havocorders.dialog;

import net.eclipse.havocorders.HavocOrders;
import net.eclipse.havocorders.ui.ScreenModel;
import net.eclipse.havocorders.manager.ItemCatalogue;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EnchantPickerScreen extends Screen {

    public EnchantPickerScreen(HavocOrders plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected String configPath() {
        return "ENCHANT-PICKER";
    }

    private int perPage() {
        return Math.max(1, plugin.getConfig().getInt("SETTINGS.ITEMS-PER-PAGE", 12));
    }

    private Map<String, String> screenPlaceholders(int size) {
        int pages = totalPages(size, perPage());
        Map<String, String> map = new HashMap<>();
        map.put("page", String.valueOf(Math.min(session.getEnchantPage() + 1, pages)));
        map.put("pages", String.valueOf(pages));
        return map;
    }

    @Override
    public String title() {
        return titleFrom(screenPlaceholders(plugin.catalogue().enchantments().size()));
    }

    @Override
    public List<String> bodyLines() {
        return resolve(lines("BODY"), screenPlaceholders(plugin.catalogue().enchantments().size()));
    }

    @Override
    public ScreenModel.Button exitButton() {
        return backButton("BACK", screenPlaceholders(plugin.catalogue().enchantments().size()),
                () -> new ItemPickerScreen(plugin, player).show());
    }

    @Override
    public List<ScreenModel.Button> buttons() {
        List<ItemCatalogue.EnchantEntry> all = plugin.catalogue().enchantments();
        Map<String, String> screen = screenPlaceholders(all.size());
        int pages = totalPages(all.size(), perPage());
        List<ScreenModel.Button> buttons = new ArrayList<>();

        for (ItemCatalogue.EnchantEntry entry : slice(all, session.getEnchantPage(), perPage())) {
            Map<String, String> placeholders = new HashMap<>(screen);
            placeholders.put("enchantment", entry.enchantmentName());
            placeholders.put("level", entry.levelLabel());
            buttons.add(configButton("ENCHANT", placeholders, entry.book().clone(), responses -> {
                session.setDraftItem(entry.book());
                success();
                new NewOrderScreen(plugin, player).show();
            }));
        }

        if (session.getEnchantPage() > 0) {
            buttons.add(configButton("PREVIOUS", screen, responses -> {
                session.setEnchantPage(session.getEnchantPage() - 1);
                click();
                show();
            }));
        }
        if (session.getEnchantPage() < pages - 1) {
            buttons.add(configButton("NEXT", screen, responses -> {
                session.setEnchantPage(session.getEnchantPage() + 1);
                click();
                show();
            }));
        }
        return buttons;
    }
}
