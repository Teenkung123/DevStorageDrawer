package com.teenkung.devstoragedrawer.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * IDs of renderer-owned entities. Null values intentionally mean that a renderer has no entity
 * for that role; this makes independently repairing each Java and Bedrock visual safe.
 */
public record DrawerDisplayLink(
        UUID javaItemDisplayId,
        UUID javaNameTextDisplayId,
        UUID javaAmountTextDisplayId,
        UUID bedrockItemDisplayId,
        UUID bedrockTextDisplayId
) {

    public static DrawerDisplayLink none() {
        return new DrawerDisplayLink(null, null, null, null, null);
    }

    public Optional<UUID> optionalJavaItemDisplayId() {
        return Optional.ofNullable(this.javaItemDisplayId);
    }

    public Optional<UUID> optionalJavaNameTextDisplayId() {
        return Optional.ofNullable(this.javaNameTextDisplayId);
    }

    public Optional<UUID> optionalJavaAmountTextDisplayId() {
        return Optional.ofNullable(this.javaAmountTextDisplayId);
    }

    public Optional<UUID> optionalBedrockItemDisplayId() {
        return Optional.ofNullable(this.bedrockItemDisplayId);
    }

    public Optional<UUID> optionalBedrockTextDisplayId() {
        return Optional.ofNullable(this.bedrockTextDisplayId);
    }

    public boolean isEmpty() {
        return this.javaItemDisplayId == null
                && this.javaNameTextDisplayId == null
                && this.javaAmountTextDisplayId == null
                && this.bedrockItemDisplayId == null
                && this.bedrockTextDisplayId == null;
    }
}
