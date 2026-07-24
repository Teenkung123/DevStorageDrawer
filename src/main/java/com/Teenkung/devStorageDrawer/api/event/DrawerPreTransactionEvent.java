package com.Teenkung.devStorageDrawer.api.event;

import com.Teenkung.devStorageDrawer.api.DrawerOperationContext;
import com.Teenkung.devStorageDrawer.api.DrawerSnapshot;
import com.Teenkung.devStorageDrawer.api.DrawerTransferType;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/** Cancellable hook fired immediately before an external API transfer is committed. */
public final class DrawerPreTransactionEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final DrawerTransferType type;
    private final DrawerSnapshot snapshot;
    private final ItemStack offeredItem;
    private final long requestedAmount;
    private final DrawerOperationContext context;
    private boolean cancelled;

    public DrawerPreTransactionEvent(
            final DrawerTransferType type,
            final DrawerSnapshot snapshot,
            final ItemStack offeredItem,
            final long requestedAmount,
            final DrawerOperationContext context
    ) {
        this.type = Objects.requireNonNull(type, "type");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.offeredItem = offeredItem == null ? null : offeredItem.clone();
        if (requestedAmount <= 0L) {
            throw new IllegalArgumentException("requestedAmount must be positive");
        }
        if (type == DrawerTransferType.DEPOSIT && offeredItem == null) {
            throw new IllegalArgumentException("A deposit event requires an offered item");
        }
        if (type == DrawerTransferType.WITHDRAW && offeredItem != null) {
            throw new IllegalArgumentException("A withdrawal event cannot have an offered item");
        }
        this.requestedAmount = requestedAmount;
        this.context = Objects.requireNonNull(context, "context");
    }

    public DrawerTransferType type() { return type; }
    public DrawerSnapshot snapshot() { return snapshot; }
    public Optional<ItemStack> offeredItem() { return offeredItem == null ? Optional.empty() : Optional.of(offeredItem.clone()); }
    public long requestedAmount() { return requestedAmount; }
    public DrawerOperationContext context() { return context; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(final boolean cancel) { this.cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
