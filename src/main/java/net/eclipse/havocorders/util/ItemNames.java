package net.eclipse.havocorders.util;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.Map;

/** Produces a readable name for any stack, including enchanted books and potions. */
public final class ItemNames {

    private ItemNames() {
    }

    /**
     * The item's real type, ignoring any custom display name. Search uses this so a
     * renamed item cannot surface under a type it is not.
     */
    public static String typeName(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return "Nothing";

        ItemMeta meta = item.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta storage && storage.hasStoredEnchants()) {
            Map.Entry<Enchantment, Integer> first = storage.getStoredEnchants().entrySet().iterator().next();
            return enchantment(first.getKey()) + " " + Text.roman(first.getValue());
        }
        if (meta instanceof PotionMeta potion) {
            PotionType type = potion.getBasePotionType();
            if (type != null) return Text.pretty(type.name()) + suffix(item.getType());
        }
        return Text.pretty(item.getType().name());
    }

    public static String display(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return "Nothing";

        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        }

        if (meta instanceof EnchantmentStorageMeta storage && storage.hasStoredEnchants()) {
            Map.Entry<Enchantment, Integer> first = storage.getStoredEnchants().entrySet().iterator().next();
            return enchantment(first.getKey()) + " " + Text.roman(first.getValue());
        }

        if (meta instanceof PotionMeta potion) {
            PotionType type = potion.getBasePotionType();
            if (type != null) {
                return Text.pretty(type.name()) + suffix(item.getType());
            }
        }

        return Text.pretty(item.getType().name());
    }

    public static String enchantment(Enchantment enchantment) {
        return Text.pretty(enchantment.getKey().getKey());
    }

    private static String suffix(Material material) {
        return switch (material) {
            case SPLASH_POTION -> " (Splash)";
            case LINGERING_POTION -> " (Lingering)";
            default -> "";
        };
    }
}
