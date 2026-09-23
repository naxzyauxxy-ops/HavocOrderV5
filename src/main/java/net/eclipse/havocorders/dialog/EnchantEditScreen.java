package net.eclipse.havocorders.dialog;

import net.eclipse.havocorders.HavocOrders;
import net.eclipse.havocorders.ui.ScreenModel;
import net.eclipse.havocorders.util.ItemNames;
import net.eclipse.havocorders.util.Text;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds enchantments to the item being ordered, so a player can ask for a Sharpness V
 * netherite sword rather than just "a netherite sword".
 *
 * Only enchantments that actually apply to the item are listed, unless unsafe
 * enchantments are switched on in config.
 */
public class EnchantEditScreen extends Screen {

    public EnchantEditScreen(HavocOrders plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected String configPath() {
        return "ENCHANT-EDIT";
    }

    private int perPage() {
        return Math.max(1, plugin.getConfig().getInt("SETTINGS.ITEMS-PER-PAGE", 27));
    }

    private ItemStack draft() {
        return session.getDraftItem();
    }

    private boolean unsafe() {
        return plugin.getConfig().getBoolean("SETTINGS.ENCHANTS.ALLOW-UNSAFE", false);
    }

    /** Enchantments offered for this item. */
    private List<Enchantment> options() {
        ItemStack item = draft();
        List<Enchantment> result = new ArrayList<>();
        if (item == null) return result;

        boolean book = item.getType() == Material.ENCHANTED_BOOK;
        for (Enchantment enchantment : Registry.ENCHANTMENT) {
            // A book can store anything; a real item only takes what fits, unless the
            // server has allowed unsafe combinations.
            if (book || unsafe() || enchantment.canEnchantItem(item)) {
                result.add(enchantment);
            }
        }
        result.sort(Comparator.comparing(ItemNames::enchantment, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    static Map<Enchantment, Integer> current(ItemStack item) {
        if (item == null || item.getItemMeta() == null) return Map.of();
        ItemMeta meta = item.getItemMeta();
        return meta instanceof EnchantmentStorageMeta storage
                ? storage.getStoredEnchants()
                : meta.getEnchants();
    }

    private Map<String, String> screen() {
        ItemStack item = draft();
        Map<Enchantment, Integer> applied = current(item);
        int pages = totalPages(options().size(), perPage());

        Map<String, String> map = new HashMap<>();
        map.put("material", item == null ? "none" : ItemNames.display(item));
        map.put("count", String.valueOf(applied.size()));
        map.put("page", String.valueOf(Math.min(session.getEnchantPage() + 1, pages)));
        map.put("pages", String.valueOf(pages));

        StringBuilder summary = new StringBuilder();
        for (Map.Entry<Enchantment, Integer> entry : applied.entrySet()) {
            if (summary.length() > 0) summary.append("&8, ");
            summary.append("&f").append(ItemNames.enchantment(entry.getKey()))
                   .append(' ').append(Text.roman(entry.getValue()));
        }
        map.put("applied", summary.length() == 0 ? "&7none" : summary.toString());
        return map;
    }

    @Override
    public String title() {
        return titleFrom(screen());
    }

    @Override
    public List<String> bodyLines() {
        List<String> body = new ArrayList<>();
        ItemStack item = draft();
        if (item != null) body.addAll(resolve(lines("BODY"), screen()));
        return body;
    }

    @Override
    public ScreenModel.Button exitButton() {
        return backButton("BACK", screen(), () -> new NewOrderScreen(plugin, player).show());
    }

    @Override
    public List<ScreenModel.Button> buttons() {
        List<Enchantment> options = options();
        Map<String, String> screen = screen();
        Map<Enchantment, Integer> applied = current(draft());
        int pages = totalPages(options.size(), perPage());
        List<ScreenModel.Button> buttons = new ArrayList<>();

        for (Enchantment enchantment : slice(options, session.getEnchantPage(), perPage())) {
            Integer level = applied.get(enchantment);
            Map<String, String> placeholders = new HashMap<>(screen);
            placeholders.put("enchantment", ItemNames.enchantment(enchantment));
            placeholders.put("level", level == null ? "" : Text.roman(level));
            placeholders.put("status", level == null ? "off" : "on");

            buttons.add(configButton(level == null ? "ENCHANT" : "ENCHANT-ACTIVE", placeholders,
                    null, responses -> {
                        click();
                        new EnchantLevelScreen(plugin, player, enchantment).show();
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

        if (!applied.isEmpty()) {
            buttons.add(configButton("CLEAR", screen, responses -> {
                ItemStack item = draft();
                if (item != null) {
                    ItemStack copy = item.clone();
                    for (Enchantment enchantment : current(copy).keySet()) {
                        removeEnchant(copy, enchantment);
                    }
                    session.setDraftItem(copy);
                }
                success();
                show();
            }));
        }
        return buttons;
    }

    static void removeEnchant(ItemStack item, Enchantment enchantment) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        if (meta instanceof EnchantmentStorageMeta storage) {
            storage.removeStoredEnchant(enchantment);
        } else {
            meta.removeEnchant(enchantment);
        }
        item.setItemMeta(meta);
    }

    static void applyEnchant(ItemStack item, Enchantment enchantment, int level, boolean unsafe) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        if (meta instanceof EnchantmentStorageMeta storage) {
            storage.addStoredEnchant(enchantment, level, unsafe);
        } else {
            meta.addEnchant(enchantment, level, unsafe);
        }
        item.setItemMeta(meta);
    }

    @Override
    public org.bukkit.inventory.ItemStack bodyIcon() {
        return draft() == null ? null : draft().clone();
    }
}
