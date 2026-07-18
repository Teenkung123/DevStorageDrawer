package com.teenkung.devstoragedrawer.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.plugin.Plugin;

/** Identifies the plugin and optional profile responsible for an external operation. */
public record DrawerOperationContext(Plugin sourcePlugin, String reason, Optional<UUID> actorId) {

    public DrawerOperationContext {
        sourcePlugin = Objects.requireNonNull(sourcePlugin, "sourcePlugin");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        actorId = Objects.requireNonNull(actorId, "actorId");
    }

    public static DrawerOperationContext of(final Plugin sourcePlugin, final String reason) {
        return new DrawerOperationContext(sourcePlugin, reason, Optional.empty());
    }

    public static DrawerOperationContext forActor(final Plugin sourcePlugin, final String reason, final UUID actorId) {
        return new DrawerOperationContext(sourcePlugin, reason, Optional.of(Objects.requireNonNull(actorId, "actorId")));
    }
}
