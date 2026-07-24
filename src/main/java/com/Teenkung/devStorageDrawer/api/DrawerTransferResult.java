package com.Teenkung.devStorageDrawer.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.inventory.ItemStack;

/** Immutable result of an API deposit or withdrawal. */
public record DrawerTransferResult(
        DrawerTransferStatus status,
        long requestedAmount,
        long acceptedAmount,
        Optional<DrawerSnapshot> snapshot,
        List<ItemStack> withdrawnItems
) {

    public DrawerTransferResult {
        status = Objects.requireNonNull(status, "status");
        if (requestedAmount < 0L || acceptedAmount < 0L || acceptedAmount > requestedAmount) {
            throw new IllegalArgumentException("Transfer amounts are invalid");
        }
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        withdrawnItems = copyItems(withdrawnItems);
        if ((status == DrawerTransferStatus.APPLIED) != (acceptedAmount > 0L)) {
            throw new IllegalArgumentException("Only an applied transfer may accept items");
        }
    }

    @Override
    public List<ItemStack> withdrawnItems() {
        return copyItems(withdrawnItems);
    }

    public long rejectedAmount() {
        return requestedAmount - acceptedAmount;
    }

    private static List<ItemStack> copyItems(final List<ItemStack> items) {
        final List<ItemStack> copy = new ArrayList<>();
        for (final ItemStack item : Objects.requireNonNull(items, "withdrawnItems")) {
            copy.add(Objects.requireNonNull(item, "withdrawnItems entry").clone());
        }
        return List.copyOf(copy);
    }
}
