package com.Teenkung.devStorageDrawer.parcel;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

/** Immutable decoded contents of a protected drawer recovery parcel. */
public record DrawerParcelData(UUID parcelId, UUID ownerId, ItemStack template, long count) {
    public DrawerParcelData {
        Objects.requireNonNull(parcelId, "parcelId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(template, "template");
        if (count < 1L) {
            throw new IllegalArgumentException("Parcel count must be positive");
        }
        template = template.clone();
        template.setAmount(1);
    }
}
