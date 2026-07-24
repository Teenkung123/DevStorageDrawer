package com.Teenkung.devStorageDrawer.hopper;

import com.Teenkung.devStorageDrawer.domain.DrawerItemIdentity;
import java.util.Objects;
import org.bukkit.block.Barrel;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Rewrites the physical barrel mirror using only real items backed by the drawer total. */
public final class DrawerProxyInventory {

    private DrawerProxyInventory() {
    }

    public static long maxRepresentable(final ItemStack template, final int outputProxySlots) {
        if (outputProxySlots < 1 || outputProxySlots > 26) {
            throw new IllegalArgumentException("outputProxySlots must be between 1 and 26");
        }
        return Math.multiplyExact((long) DrawerItemIdentity.nativeMaxStackSize(template), (long) outputProxySlots + 1L);
    }

    /**
     * Applies a known-safe physical target. The caller must persist a rebalance journal before
     * calling this method when the target redistributes existing barrel stock.
     */
    public static boolean rewrite(
            final Barrel barrel,
            final ItemStack template,
            final long targetCount,
            final int outputProxySlots
    ) {
        Objects.requireNonNull(barrel, "barrel");
        DrawerItemIdentity.requireStorageCandidate(template, "Proxy item template");
        if (targetCount < 0L || targetCount > maxRepresentable(template, outputProxySlots)) {
            throw new IllegalArgumentException("targetCount is outside the configured proxy capacity");
        }

        final Inventory inventory = barrel.getInventory();
        inventory.clear();
        final int maxStackSize = DrawerItemIdentity.nativeMaxStackSize(template);
        if (usesDenseGuard(targetCount, outputProxySlots, maxStackSize)) {
            distributeDense(inventory, template, targetCount, maxStackSize);
            return true;
        }

        long remaining = targetCount;
        for (int slot = 1; slot <= outputProxySlots && remaining > 0L; slot++) {
            final int amount = (int) Math.min(remaining, (long) maxStackSize);
            final ItemStack stack = template.clone();
            stack.setAmount(amount);
            inventory.setItem(slot, stack);
            remaining -= amount;
        }
        if (remaining > 0L) {
            final ItemStack inputStack = template.clone();
            inputStack.setAmount(Math.toIntExact(remaining));
            inventory.setItem(0, inputStack);
            remaining = 0L;
        }
        if (remaining != 0L) {
            throw new IllegalStateException("Proxy target did not fit into the barrel mirror");
        }
        // Barrel#getInventory returns the placed block's live inventory. Calling BlockState#update
        // here would reapply this snapshot's older inventory and erase the rewrite.
        return true;
    }

    /**
     * Dense mode needs one full native stack plus one real item in every remaining barrel slot.
     * Below that threshold, packing stock into an output stack is more important because the
     * Paper hopper-search guard independently rejects wrong input before it enters the barrel.
     */
    public static boolean usesDenseGuard(final long targetCount, final int outputProxySlots, final int maxStackSize) {
        return outputProxySlots == 26 && maxStackSize > 1
                && targetCount >= (long) maxStackSize + 26L;
    }

    /** Returns whether the physical inventory needs a same-count repack to restore hopper output. */
    public static boolean needsRepack(
            final Barrel barrel,
            final ItemStack template,
            final long targetCount,
            final int outputProxySlots
    ) {
        Objects.requireNonNull(barrel, "barrel");
        DrawerItemIdentity.requireStorageCandidate(template, "Proxy item template");
        if (targetCount < 0L || targetCount > maxRepresentable(template, outputProxySlots)) {
            throw new IllegalArgumentException("targetCount is outside the configured proxy capacity");
        }

        final int[] expected = expectedLayout(targetCount, outputProxySlots,
                DrawerItemIdentity.nativeMaxStackSize(template));
        final ItemStack[] actual = barrel.getInventory().getContents();
        for (int slot = 0; slot < actual.length; slot++) {
            final ItemStack stack = actual[slot];
            if (expected[slot] == 0) {
                if (DrawerItemIdentity.isStorageCandidate(stack)) {
                    return true;
                }
                continue;
            }
            if (!DrawerItemIdentity.matches(template, stack) || stack.getAmount() != expected[slot]) {
                return true;
            }
        }
        return false;
    }

    private static void distributeDense(
            final Inventory inventory,
            final ItemStack template,
            final long targetCount,
            final int maxStackSize
    ) {
        long remaining = targetCount;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            final int slotsAfter = inventory.getSize() - slot - 1;
            // Keep one real item for every later slot, then pack as much of the remaining mirror
            // as possible into the earliest slot. Hoppers iterate barrel slots from the start, so
            // this preserves a full transfer-sized stack while still leaving no empty slot for a
            // wrong item to enter.
            final int amount = (int) Math.min((long) maxStackSize, remaining - slotsAfter);
            final ItemStack stack = template.clone();
            stack.setAmount(amount);
            inventory.setItem(slot, stack);
            remaining -= amount;
        }
        if (remaining != 0L) {
            throw new IllegalStateException("Dense proxy target did not fit into the barrel mirror");
        }
    }

    private static int[] expectedLayout(final long targetCount, final int outputProxySlots, final int maxStackSize) {
        final int[] expected = new int[27];
        long remaining = targetCount;
        if (usesDenseGuard(targetCount, outputProxySlots, maxStackSize)) {
            for (int slot = 0; slot < expected.length; slot++) {
                final int slotsAfter = expected.length - slot - 1;
                expected[slot] = (int) Math.min((long) maxStackSize, remaining - slotsAfter);
                remaining -= expected[slot];
            }
        } else {
            for (int slot = 1; slot <= outputProxySlots && remaining > 0L; slot++) {
                expected[slot] = (int) Math.min(remaining, (long) maxStackSize);
                remaining -= expected[slot];
            }
            if (remaining > 0L) {
                expected[0] = Math.toIntExact(remaining);
                remaining = 0L;
            }
        }
        if (remaining != 0L) {
            throw new IllegalStateException("Proxy target did not fit into the barrel mirror");
        }
        return expected;
    }

    /**
     * Removes only existing proxy items without redistributing the rest of the barrel. This keeps
     * player withdrawals out of the rebalance path, whose durable journal is intentionally
     * reserved for complete proxy rewrites.
     */
    public static boolean remove(final Barrel barrel, final ItemStack template, final long amount) {
        Objects.requireNonNull(barrel, "barrel");
        DrawerItemIdentity.requireStorageCandidate(template, "Proxy item template");
        if (amount <= 0L) {
            return amount == 0L;
        }

        final Inventory inventory = barrel.getInventory();
        final ItemStack[] before = copyContents(inventory.getContents());
        long remaining = amount;
        for (int slot = 0; slot < inventory.getSize() && remaining > 0L; slot++) {
            final ItemStack current = inventory.getItem(slot);
            if (current == null || current.getType().isAir() || current.getAmount() <= 0) {
                continue;
            }
            if (!DrawerItemIdentity.matches(template, current)) {
                inventory.setContents(before);
                return false;
            }
            final int removed = (int) Math.min(remaining, (long) current.getAmount());
            final int nextAmount = current.getAmount() - removed;
            if (nextAmount == 0) {
                inventory.setItem(slot, null);
            } else {
                current.setAmount(nextAmount);
                inventory.setItem(slot, current);
            }
            remaining -= removed;
        }
        if (remaining != 0L) {
            inventory.setContents(before);
            return false;
        }
        return true;
    }

    private static ItemStack[] copyContents(final ItemStack[] contents) {
        final ItemStack[] copy = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            copy[index] = contents[index] == null ? null : contents[index].clone();
        }
        return copy;
    }
}
