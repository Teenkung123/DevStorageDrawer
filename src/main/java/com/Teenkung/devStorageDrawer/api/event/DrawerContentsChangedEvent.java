package com.Teenkung.devStorageDrawer.api.event;

import com.Teenkung.devStorageDrawer.api.DrawerChangeCause;
import com.Teenkung.devStorageDrawer.api.DrawerSnapshot;
import java.util.Objects;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired on the drawer's owning region after a logical state change has been persisted. */
public final class DrawerContentsChangedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final DrawerSnapshot snapshot;
    private final DrawerChangeCause cause;

    public DrawerContentsChangedEvent(final DrawerSnapshot snapshot, final DrawerChangeCause cause) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.cause = Objects.requireNonNull(cause, "cause");
    }

    public DrawerSnapshot snapshot() { return snapshot; }
    public DrawerChangeCause cause() { return cause; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
