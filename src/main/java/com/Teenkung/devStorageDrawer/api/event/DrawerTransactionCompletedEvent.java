package com.teenkung.devstoragedrawer.api.event;

import com.teenkung.devstoragedrawer.api.DrawerOperationContext;
import com.teenkung.devstoragedrawer.api.DrawerTransferResult;
import com.teenkung.devstoragedrawer.api.DrawerTransferType;
import java.util.Objects;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after a successful external API transfer has committed and the drawer has refreshed. */
public final class DrawerTransactionCompletedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final DrawerTransferType type;
    private final DrawerTransferResult result;
    private final DrawerOperationContext context;

    public DrawerTransactionCompletedEvent(
            final DrawerTransferType type,
            final DrawerTransferResult result,
            final DrawerOperationContext context
    ) {
        this.type = Objects.requireNonNull(type, "type");
        this.result = Objects.requireNonNull(result, "result");
        this.context = Objects.requireNonNull(context, "context");
    }

    public DrawerTransferType type() { return type; }
    public DrawerTransferResult result() { return result; }
    public DrawerOperationContext context() { return context; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
