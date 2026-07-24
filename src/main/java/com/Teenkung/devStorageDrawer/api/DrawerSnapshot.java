package com.Teenkung.devStorageDrawer.api;

import java.util.Objects;
import java.util.Optional;
import org.bukkit.inventory.ItemStack;

/** Immutable logical view that never exposes the real barrel inventory or PDC state. */
public final class DrawerSnapshot {

    private final DrawerLocation location;
    private final String tierId;
    private final ItemStack template;
    private final long storedAmount;
    private final long capacity;
    private final long physicalProxyAmount;

    public DrawerSnapshot(
            final DrawerLocation location,
            final String tierId,
            final ItemStack template,
            final long storedAmount,
            final long capacity,
            final long physicalProxyAmount
    ) {
        this.location = Objects.requireNonNull(location, "location");
        if (tierId == null || tierId.isBlank()) {
            throw new IllegalArgumentException("tierId must not be blank");
        }
        if (storedAmount < 0L || capacity <= 0L || storedAmount > capacity || physicalProxyAmount < 0L
                || physicalProxyAmount > storedAmount) {
            throw new IllegalArgumentException("Drawer snapshot counts are invalid");
        }
        if (template == null && storedAmount != 0L) {
            throw new IllegalArgumentException("A non-empty drawer snapshot requires a template");
        }
        this.tierId = tierId;
        this.template = template == null ? null : template.clone();
        this.storedAmount = storedAmount;
        this.capacity = capacity;
        this.physicalProxyAmount = physicalProxyAmount;
    }

    public DrawerLocation location() { return location; }
    public String tierId() { return tierId; }
    public Optional<ItemStack> template() { return template == null ? Optional.empty() : Optional.of(template.clone()); }
    public long storedAmount() { return storedAmount; }
    public long capacity() { return capacity; }
    public long physicalProxyAmount() { return physicalProxyAmount; }
    public long availableCapacity() { return capacity - storedAmount; }
    public boolean isEmpty() { return storedAmount == 0L; }
    public boolean isFull() { return storedAmount == capacity; }
}
