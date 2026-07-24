package com.Teenkung.devStorageDrawer.domain;

import java.util.Objects;
import org.bukkit.inventory.ItemStack;

/**
 * Item identity deliberately uses Paper's public {@link ItemStack#isSimilar(ItemStack)} contract.
 * That compares the item type and its data components while ignoring the mutable item amount.
 */
public final class DrawerItemIdentity {

    private DrawerItemIdentity() {
    }

    public static boolean isStorageCandidate(final ItemStack item) {
        return item != null && item.getAmount() > 0 && !item.getType().isAir();
    }

    public static ItemStack templateOf(final ItemStack item) {
        requireStorageCandidate(item, "Drawer template");
        final ItemStack template = item.clone();
        template.setAmount(1);
        return template;
    }

    public static boolean matches(final ItemStack template, final ItemStack candidate) {
        return isStorageCandidate(template)
                && isStorageCandidate(candidate)
                && template.isSimilar(candidate);
    }

    public static int nativeMaxStackSize(final ItemStack item) {
        requireStorageCandidate(item, "Drawer item");
        final int maxStackSize = item.getMaxStackSize();
        if (maxStackSize <= 0) {
            throw new DrawerValidationException("Item " + item.getType() + " has an invalid maximum stack size");
        }
        return maxStackSize;
    }

    public static void requireStorageCandidate(final ItemStack item, final String label) {
        if (!isStorageCandidate(item)) {
            throw new DrawerValidationException(Objects.requireNonNullElse(label, "Drawer item") + " must be a non-air item");
        }
    }
}
