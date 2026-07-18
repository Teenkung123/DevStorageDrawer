package com.teenkung.devstoragedrawer.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Cached server hopper batch sizes used by the eventless destination preflight. */
public record HopperTransferPolicy(int defaultAmount, Map<String, Integer> worldAmounts) {

    public HopperTransferPolicy {
        if (defaultAmount < 1) {
            throw new IllegalArgumentException("Default hopper transfer amount must be positive");
        }
        Objects.requireNonNull(worldAmounts, "worldAmounts");
        final Map<String, Integer> validated = new LinkedHashMap<>();
        worldAmounts.forEach((worldName, amount) -> {
            if (worldName == null || worldName.isBlank()) {
                throw new IllegalArgumentException("Hopper transfer world name must not be blank");
            }
            if (amount == null || amount < 1) {
                throw new IllegalArgumentException("Hopper transfer amount for " + worldName + " must be positive");
            }
            validated.put(worldName, amount);
        });
        worldAmounts = Map.copyOf(validated);
    }

    public int amountFor(final String worldName) {
        return this.worldAmounts.getOrDefault(Objects.requireNonNull(worldName, "worldName"), this.defaultAmount);
    }

    /**
     * Spigot limits a configured hopper batch to both the available source count and the item's
     * own stack limit. Mirroring that rule avoids under-guarding large batches and over-blocking
     * unstackable or component-limited items.
     */
    public int guardedAmountFor(
            final String worldName,
            final int sourceAmount,
            final int itemMaxStackSize
    ) {
        if (sourceAmount < 1 || itemMaxStackSize < 1) {
            throw new IllegalArgumentException("Hopper source amount and item stack size must be positive");
        }
        return Math.min(Math.min(sourceAmount, itemMaxStackSize), amountFor(worldName));
    }
}
