package com.Teenkung.devStorageDrawer.domain;

import java.util.Objects;
import org.bukkit.inventory.ItemStack;

/** Safe arithmetic used by all drawer storage operations. */
public final class DrawerCapacity {

    private DrawerCapacity() {
    }

    /**
     * Converts a configured number of native item stacks into an item capacity.
     *
     * @throws DrawerValidationException if the tier or item's maximum stack size is invalid, or
     *                                   the result cannot fit in a signed {@code long}
     */
    public static long fromStacks(final long stackCount, final ItemStack template) {
        if (stackCount <= 0L) {
            throw new DrawerValidationException("Drawer stack capacity must be greater than zero");
        }

        final int maxStackSize = DrawerItemIdentity.nativeMaxStackSize(template);
        try {
            return Math.multiplyExact(stackCount, (long) maxStackSize);
        } catch (final ArithmeticException exception) {
            throw new DrawerValidationException(
                    "Drawer capacity overflows a signed long: " + stackCount + " stacks of " + maxStackSize,
                    exception
            );
        }
    }

    public static long checkedAdd(final long left, final long right, final String context) {
        requireNonNegative(left, "left value");
        requireNonNegative(right, "right value");
        try {
            return Math.addExact(left, right);
        } catch (final ArithmeticException exception) {
            throw new DrawerInvariantViolationException(
                    Objects.requireNonNullElse(context, "Drawer count") + " overflows a signed long",
                    exception
            );
        }
    }

    public static long checkedSubtract(final long left, final long right, final String context) {
        requireNonNegative(left, "left value");
        requireNonNegative(right, "right value");
        if (right > left) {
            throw new DrawerInvariantViolationException(
                    Objects.requireNonNullElse(context, "Drawer count") + " would become negative"
            );
        }
        return left - right;
    }

    static long totalAfterMirrorDelta(
            final long storedTotal,
            final long expectedMirrorCount,
            final long observedMirrorCount,
            final long capacity
    ) {
        final long total = totalAfterMirrorDeltaWithoutCapacityLimit(
                storedTotal,
                expectedMirrorCount,
                observedMirrorCount
        );
        requireNonNegative(capacity, "drawer capacity");
        if (total > capacity) {
            throw new DrawerInvariantViolationException("Drawer mirror delta exceeds capacity");
        }
        return total;
    }

    static long totalAfterMirrorDeltaWithoutCapacityLimit(
            final long storedTotal,
            final long expectedMirrorCount,
            final long observedMirrorCount
    ) {
        requireNonNegative(storedTotal, "drawer stored total");
        requireNonNegative(expectedMirrorCount, "expected drawer mirror count");
        requireNonNegative(observedMirrorCount, "observed drawer mirror count");

        return observedMirrorCount >= expectedMirrorCount
                ? checkedAdd(
                storedTotal,
                observedMirrorCount - expectedMirrorCount,
                "Drawer mirror input delta"
        )
                : checkedSubtract(
                storedTotal,
                expectedMirrorCount - observedMirrorCount,
                "Drawer mirror output delta"
        );
    }

    /**
     * Raises a preferred mirror target until its remaining physical inventory space is no greater
     * than the drawer's remaining logical capacity. Matching eventless hopper input then fills at
     * most the amount the drawer can really accept.
     */
    static long capacityProtectedMirrorTarget(
            final long total,
            final long capacity,
            final long mirrorCapacity,
            final long preferredTarget
    ) {
        requireNonNegative(total, "drawer total");
        requireNonNegative(capacity, "drawer capacity");
        requireNonNegative(mirrorCapacity, "physical mirror capacity");
        requireNonNegative(preferredTarget, "preferred physical mirror target");
        if (total > capacity) {
            throw new DrawerInvariantViolationException("Drawer total exceeds capacity");
        }
        if (preferredTarget > total || preferredTarget > mirrorCapacity) {
            throw new DrawerInvariantViolationException("Preferred physical mirror target is invalid");
        }

        final long availableCapacity = capacity - total;
        final long minimumProtectedTarget = availableCapacity >= mirrorCapacity
                ? 0L
                : mirrorCapacity - availableCapacity;
        return Math.max(preferredTarget, Math.min(total, minimumProtectedTarget));
    }

    public static void requireNonNegative(final long value, final String label) {
        if (value < 0L) {
            throw new DrawerInvariantViolationException(label + " must not be negative");
        }
    }
}
