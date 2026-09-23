package net.eclipse.havocorders.manager;

import net.eclipse.havocorders.HavocOrders;
import net.eclipse.havocorders.util.Category;
import net.eclipse.havocorders.util.ItemNames;
import net.eclipse.havocorders.util.Text;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything a player may order, built once on enable and on reload.
 *
 * Entries are pre-bucketed by category and carry a lowercased name, so the picker's
 * filter is a map lookup and its search is a single pass of substring checks over an
 * already-narrowed list. Nothing is allocated per keystroke.
 */
public class ItemCatalogue {

    public record Entry(ItemStack stack, String name, String lowerName, Category category) {
    }

    public record EnchantEntry(ItemStack book, String enchantmentName, String levelLabel) {
    }

    private final HavocOrders plugin;
    private final List<Entry> all = new ArrayList<>();
    private final Map<Category, List<Entry>> byCategory = new EnumMap<>(Category.class);
    private final List<EnchantEntry> enchantments = new ArrayList<>();

    public ItemCatalogue(HavocOrders plugin) {
        this.plugin = plugin;
    }

    public List<Entry> entries() {
        return all;
    }

    public List<EnchantEntry> enchantments() {
        return enchantments;
    }

    /** Filter + substring search. Returns an unmodifiable snapshot safe to slice. */
    public List<Entry> search(Category category, String loweredQuery) {
        List<Entry> pool = category == Category.ALL
                ? all
                : byCategory.getOrDefault(category, List.of());
        if (loweredQuery == null || loweredQuery.isEmpty()) return pool;

        List<Entry> matched = new ArrayList<>();
        for (Entry entry : pool) {
            if (entry.lowerName().contains(loweredQuery)) matched.add(entry);
        }
        return matched;
    }

    public void build() {
        all.clear();
        byCategory.clear();
        enchantments.clear();

        for (Material material : Material.values()) {
            if (material.isLegacy() || material == Material.AIR || !material.isItem()) continue;
            if (plugin.isBlocked(material)) continue;

            if (material == Material.POTION || material == Material.SPLASH_POTION
                    || material == Material.LINGERING_POTION) {
                String suffix = potionSuffix(material);
                for (PotionType type : PotionType.values()) {
                    ItemStack stack = new ItemStack(material);
                    if (stack.getItemMeta() instanceof PotionMeta meta) {
                        meta.setBasePotionType(type);
                        stack.setItemMeta(meta);
                    }
                    add(stack, Text.pretty(type.name()) + suffix);
                }
                continue;
            }

            ItemStack stack = new ItemStack(material);
            add(stack, ItemNames.display(stack));
        }

        // Allowed spawners are added after the vanilla sweep because they are exempt from
        // the blacklist: SPAWNER is blocked as a raw item, but a HavocSpawners spawner an
        // admin explicitly allowed is a different thing.
        for (SpawnerCatalogue.Allowed spawner : plugin.spawnerCatalogue().entries()) {
            add(spawner.template().clone(), spawner.name());
        }

        all.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        for (List<Entry> bucket : byCategory.values()) {
            bucket.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        }

        buildEnchantments();
        plugin.getLogger().info("Item picker loaded with " + all.size() + " entries and "
                + enchantments.size() + " enchantment options.");
    }

    private void add(ItemStack stack, String name) {
        Category category = Category.of(stack.getType());
        Entry entry = new Entry(stack, name, name.toLowerCase(Locale.ROOT), category);
        all.add(entry);
        byCategory.computeIfAbsent(category, key -> new ArrayList<>()).add(entry);
    }

    private void buildEnchantments() {
        List<Enchantment> sorted = new ArrayList<>();
        for (Enchantment enchantment : Registry.ENCHANTMENT) {
            sorted.add(enchantment);
        }
        sorted.sort(Comparator.comparing(ItemNames::enchantment, String.CASE_INSENSITIVE_ORDER));

        for (Enchantment enchantment : sorted) {
            for (int level = 1; level <= Math.max(1, enchantment.getMaxLevel()); level++) {
                ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
                if (book.getItemMeta() instanceof EnchantmentStorageMeta meta) {
                    meta.addStoredEnchant(enchantment, level, true);
                    book.setItemMeta(meta);
                }
                enchantments.add(new EnchantEntry(book,
                        ItemNames.enchantment(enchantment), Text.roman(level)));
            }
        }
    }

    private String potionSuffix(Material material) {
        return switch (material) {
            case SPLASH_POTION -> " (Splash)";
            case LINGERING_POTION -> " (Lingering)";
            default -> "";
        };
    }
}
