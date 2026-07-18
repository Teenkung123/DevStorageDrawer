package com.teenkung.devstoragedrawer.domain;

import java.math.BigInteger;

/** Pure vanilla comparator math shared by storage mirroring and redstone diagnostics. */
public final class DrawerComparatorLevel {

    private static final BigInteger FOURTEEN = BigInteger.valueOf(14L);
    private static final long BARREL_SLOTS = 27L;

    private DrawerComparatorLevel() {
    }

    public static int level(final long total, final long capacity) {
        if (total < 0L || capacity <= 0L || total > capacity) {
            throw new IllegalArgumentException("Comparator total must be between zero and capacity");
        }
        if (total == 0L) {
            return 0;
        }
        if (total == capacity) {
            return 15;
        }
        final BigInteger scaled = BigInteger.valueOf(total).multiply(FOURTEEN)
                .divide(BigInteger.valueOf(capacity));
        return 1 + scaled.intValueExact();
    }

    /**
     * Returns the smallest real-item mirror that makes a vanilla 27-slot barrel emit the logical
     * drawer level. A legacy capacity below 27 stacks may not contain enough items to reach that
     * signal, in which case all available stock is mirrored without inventing proxy items.
     */
    public static long physicalProxyTarget(final long total, final long capacity, final int nativeMaxStackSize) {
        if (nativeMaxStackSize < 1) {
            throw new IllegalArgumentException("nativeMaxStackSize must be positive");
        }
        final int desiredLevel = level(total, capacity);
        if (desiredLevel == 0) {
            return 0L;
        }
        if (desiredLevel == 1) {
            return 1L;
        }
        final BigInteger barrelCapacity = BigInteger.valueOf(BARREL_SLOTS)
                .multiply(BigInteger.valueOf(nativeMaxStackSize));
        final BigInteger numerator = barrelCapacity.multiply(BigInteger.valueOf(desiredLevel - 1L));
        final long required = ceilingDivide(numerator, FOURTEEN).longValueExact();
        return Math.min(total, required);
    }

    private static BigInteger ceilingDivide(final BigInteger numerator, final BigInteger denominator) {
        final BigInteger[] division = numerator.divideAndRemainder(denominator);
        return division[1].signum() == 0 ? division[0] : division[0].add(BigInteger.ONE);
    }
}
