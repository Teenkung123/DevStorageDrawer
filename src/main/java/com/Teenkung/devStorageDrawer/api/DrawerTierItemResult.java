package com.teenkung.devstoragedrawer.api;

import java.util.Objects;
import java.util.Optional;
import org.bukkit.inventory.ItemStack;

/** Result of {@link DevStorageDrawerApi#createTierItem(String, int)}. */
public record DrawerTierItemResult(DrawerTierItemStatus status, Optional<ItemStack> item) {

    public DrawerTierItemResult {
        status = Objects.requireNonNull(status, "status");
        item = Objects.requireNonNull(item, "item").map(ItemStack::clone);
        if ((status == DrawerTierItemStatus.CREATED) != item.isPresent()) {
            throw new IllegalArgumentException("Only a created tier item result may contain an item");
        }
    }

    @Override
    public Optional<ItemStack> item() {
        return item.map(ItemStack::clone);
    }

    public static DrawerTierItemResult created(final ItemStack item) {
        return new DrawerTierItemResult(DrawerTierItemStatus.CREATED, Optional.of(item));
    }

    public static DrawerTierItemResult of(final DrawerTierItemStatus status) {
        return new DrawerTierItemResult(status, Optional.empty());
    }
}
