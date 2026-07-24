package com.Teenkung.devStorageDrawer.config;

import java.util.Map;
import java.util.Objects;
import org.bukkit.entity.ItemDisplay;

/** Immutable, fully validated settings snapshot used by all runtime services. */
public record DrawerSettings(
        Map<String, DrawerTierDefinition> tiers,
        Interaction interaction,
        Visuals visuals,
        Bedrock bedrock,
        Automation automation,
        Redstone redstone,
        Parcels parcels
) {
    public DrawerSettings {
        tiers = Map.copyOf(Objects.requireNonNull(tiers, "tiers"));
        if (tiers.isEmpty()) {
            throw new IllegalArgumentException("At least one drawer tier is required");
        }
        Objects.requireNonNull(interaction, "interaction");
        Objects.requireNonNull(visuals, "visuals");
        Objects.requireNonNull(bedrock, "bedrock");
        Objects.requireNonNull(automation, "automation");
        Objects.requireNonNull(redstone, "redstone");
        Objects.requireNonNull(parcels, "parcels");
        if (redstone.logicalComparatorOutput()) {
            if (!automation.enabled() || automation.outputProxySlots() != 26) {
                throw new IllegalArgumentException(
                        "Logical comparator output requires automation.enabled and exactly 26 output proxy slots"
                );
            }
            for (final DrawerTierDefinition definition : tiers.values()) {
                if (definition.tier().stackCapacity() < 27L) {
                    throw new IllegalArgumentException(
                            "Logical comparator output requires every tier to hold at least 27 stacks: "
                                    + definition.tier().id()
                    );
                }
            }
        }
    }

    public record Interaction(int bulkDepositWindowTicks, boolean requireFacingFace) {
        public Interaction {
            if (bulkDepositWindowTicks < 1) {
                throw new IllegalArgumentException("bulkDepositWindowTicks must be positive");
            }
        }
    }

    public record Visuals(
            boolean enabled,
            boolean repairOnChunkLoad,
            long updateDelayTicks,
            double frontOffset,
            boolean itemEnabled,
            float itemScale,
            float itemDepthScale,
            double itemOffsetY,
            ItemDisplay.ItemDisplayTransform itemTransform,
            boolean textEnabled,
            float textScale,
            int textLineWidth,
            double nameOffsetY,
            double amountOffsetY
    ) {
        public Visuals {
            if (updateDelayTicks < 1) {
                throw new IllegalArgumentException("updateDelayTicks must be positive");
            }
            Objects.requireNonNull(itemTransform, "itemTransform");
            if (frontOffset < 0D || itemScale <= 0F || itemDepthScale <= 0F
                    || textScale <= 0F || textLineWidth < 1) {
                throw new IllegalArgumentException("visual front offset, scales, and line width must be positive");
            }
        }
    }

    public record Bedrock(
            boolean enabled,
            boolean required,
            boolean armorStandShowItem,
            boolean armorStandShowName,
            boolean armorStandMarker,
            boolean armorStandInvisible
    ) {
    }

    public record Automation(
            boolean enabled,
            int outputProxySlots,
            long rebalanceDelayTicks,
            boolean recoverJournalOnLoad,
            boolean debugLogging,
            long fallbackPollIntervalTicks,
            HopperTransferPolicy hopperTransfers
    ) {
        public Automation {
            if (outputProxySlots < 1 || outputProxySlots > 26) {
                throw new IllegalArgumentException("outputProxySlots must be between 1 and 26");
            }
            if (rebalanceDelayTicks < 1) {
                throw new IllegalArgumentException("automation delay must be positive");
            }
            if (fallbackPollIntervalTicks < 1) {
                throw new IllegalArgumentException("automation fallback poll interval must be positive");
            }
            Objects.requireNonNull(hopperTransfers, "hopperTransfers");
        }
    }

    public record Redstone(
            boolean logicalComparatorOutput,
            long comparatorRecalculateDelayTicks,
            boolean protectFromExplosions,
            boolean preventPistonMovement
    ) {
        public Redstone {
            if (comparatorRecalculateDelayTicks < 1) {
                throw new IllegalArgumentException("comparatorRecalculateDelayTicks must be positive");
            }
        }
    }

    public record Parcels(
            boolean enabled,
            boolean dropOnBreak
    ) {
    }
}
