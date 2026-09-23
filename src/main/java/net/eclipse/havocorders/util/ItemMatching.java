package net.eclipse.havocorders.util;

import net.eclipse.havocorders.integration.SpawnerSupport;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;

/**
 * Decides whether a carried item satisfies an order.
 *
 * ItemStack#isSimilar compares every scrap of item data, which is right for a stack of
 * cobblestone and wrong for anything that changes as it is used. An elytra someone has
 * actually flown is a different item to isSimilar than a fresh one, so an order for one
 * could never be filled - the reason elytra deliveries kept failing.
 */
public final class ItemMatching {

    private static SpawnerSupport spawners;
    private static boolean atLeastEnchants = true;
    private static boolean ignoreDamage = true;
    private static int minDurabilityPercent;

    private ItemMatching() {
    }

    public static void setSpawnerSupport(SpawnerSupport support) {
        spawners = support;
    }

    public static void configure(boolean atLeast, boolean ignoreWear, int minDurability) {
        atLeastEnchants = atLeast;
        ignoreDamage = ignoreWear;
        minDurabilityPercent = Math.max(0, Math.min(100, minDurability));
    }

    public static boolean matches(ItemStack template, ItemStack candidate) {
        if (template == null || candidate == null) return false;

        if (spawners != null && spawners.isAvailable() && spawners.isSpawner(template)) {
            return spawners.deliverable(template, candidate);
        }

        if (template.getType() != candidate.getType()) return false;
        if (!durabilityAcceptable(candidate)) return false;

        ItemStack left = normalise(template);
        ItemStack right = normalise(candidate);

        if (!atLeastEnchants) {
            return left.isSimilar(right);
        }

        // Compare everything except enchantments, then require the candidate to carry at
        // least what was asked for. Extra enchantments are a bonus, not a mismatch.
        Map<Enchantment, Integer> wanted = enchants(left);
        ItemStack bareLeft = stripEnchants(left);
        ItemStack bareRight = stripEnchants(right);
        if (!bareLeft.isSimilar(bareRight)) return false;

        Map<Enchantment, Integer> have = enchants(right);
        for (Map.Entry<Enchantment, Integer> entry : wanted.entrySet()) {
            Integer level = have.get(entry.getKey());
            if (level == null || level < entry.getValue()) return false;
        }
        return true;
    }

    /** Rejects items too worn to be worth what was ordered. */
    private static boolean durabilityAcceptable(ItemStack candidate) {
        if (minDurabilityPercent <= 0) return true;
        short max = candidate.getType().getMaxDurability();
        if (max <= 0) return true;
        if (!(candidate.getItemMeta() instanceof Damageable damageable)) return true;
        int remaining = max - damageable.getDamage();
        return remaining * 100 >= minDurabilityPercent * max;
    }

    private static ItemStack normalise(ItemStack stack) {
        ItemStack copy = stack.clone();
        copy.setAmount(1);
        if (ignoreDamage && copy.getItemMeta() instanceof Damageable damageable
                && copy.getType().getMaxDurability() > 0) {
            damageable.setDamage(0);
            copy.setItemMeta(damageable);
        }
        return copy;
    }

    private static Map<Enchantment, Integer> enchants(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return Map.of();
        return meta instanceof EnchantmentStorageMeta storage
                ? storage.getStoredEnchants()
                : meta.getEnchants();
    }

    private static ItemStack stripEnchants(ItemStack stack) {
        ItemStack copy = stack.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) return copy;
        if (meta instanceof EnchantmentStorageMeta storage) {
            for (Enchantment enchantment : Map.copyOf(storage.getStoredEnchants()).keySet()) {
                storage.removeStoredEnchant(enchantment);
            }
        } else {
            for (Enchantment enchantment : Map.copyOf(meta.getEnchants()).keySet()) {
                meta.removeEnchant(enchantment);
            }
        }
        copy.setItemMeta(meta);
        return copy;
    }
}
