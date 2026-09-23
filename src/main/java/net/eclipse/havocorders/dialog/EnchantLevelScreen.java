package net.eclipse.havocorders.dialog;

import net.eclipse.havocorders.HavocOrders;
import net.eclipse.havocorders.ui.ScreenModel;
import net.eclipse.havocorders.util.ItemNames;
import net.eclipse.havocorders.util.Text;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Picks the level for one enchantment, or takes it off again. */
public class EnchantLevelScreen extends Screen {

    private final Enchantment enchantment;

    public EnchantLevelScreen(HavocOrders plugin, Player player, Enchantment enchantment) {
        super(plugin, player);
        this.enchantment = enchantment;
    }

    @Override
    protected String configPath() {
        return "ENCHANT-LEVEL";
    }

    private boolean unsafe() {
        return plugin.getConfig().getBoolean("SETTINGS.ENCHANTS.ALLOW-UNSAFE", false);
    }

    private int maxLevel() {
        int natural = Math.max(1, enchantment.getMaxLevel());
        if (!unsafe()) return natural;
        return Math.max(natural, plugin.getConfig().getInt("SETTINGS.ENCHANTS.MAX-UNSAFE-LEVEL", 10));
    }

    private Map<String, String> screen() {
        ItemStack draft = session.getDraftItem();
        Integer level = EnchantEditScreen.current(draft).get(enchantment);
        Map<String, String> map = new HashMap<>();
        map.put("enchantment", ItemNames.enchantment(enchantment));
        map.put("material", draft == null ? "none" : ItemNames.display(draft));
        map.put("current", level == null ? "none" : Text.roman(level));
        map.put("max", Text.roman(maxLevel()));
        return map;
    }

    @Override
    public String title() {
        return titleFrom(screen());
    }

    @Override
    public List<String> bodyLines() {
        List<String> body = new ArrayList<>();
        ItemStack draft = session.getDraftItem();
        if (draft != null) body.addAll(resolve(lines("BODY"), screen()));
        return body;
    }

    @Override
    public ScreenModel.Button exitButton() {
        return backButton("BACK", screen(), () -> new EnchantEditScreen(plugin, player).show());
    }

    @Override
    public List<ScreenModel.Button> buttons() {
        Map<String, String> screen = screen();
        List<ScreenModel.Button> buttons = new ArrayList<>();

        for (int level = 1; level <= maxLevel(); level++) {
            int chosen = level;
            Map<String, String> placeholders = new HashMap<>(screen);
            placeholders.put("level", Text.roman(chosen));
            buttons.add(configButton("LEVEL", placeholders, responses -> {
                ItemStack draft = session.getDraftItem();
                if (draft == null) {
                    deny();
                    new NewOrderScreen(plugin, player).show();
                    return;
                }
                ItemStack copy = draft.clone();
                EnchantEditScreen.applyEnchant(copy, enchantment, chosen, unsafe());
                session.setDraftItem(copy);
                success();
                new EnchantEditScreen(plugin, player).show();
            }));
        }

        if (EnchantEditScreen.current(session.getDraftItem()).containsKey(enchantment)) {
            buttons.add(configButton("REMOVE", screen, responses -> {
                ItemStack draft = session.getDraftItem();
                if (draft != null) {
                    ItemStack copy = draft.clone();
                    EnchantEditScreen.removeEnchant(copy, enchantment);
                    session.setDraftItem(copy);
                }
                success();
                new EnchantEditScreen(plugin, player).show();
            }));
        }
        return buttons;
    }

    @Override
    public org.bukkit.inventory.ItemStack bodyIcon() {
        return session.getDraftItem() == null ? null : session.getDraftItem().clone();
    }
}
