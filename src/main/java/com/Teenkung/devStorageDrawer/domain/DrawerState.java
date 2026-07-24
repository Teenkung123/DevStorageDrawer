package com.Teenkung.devStorageDrawer.domain;

import java.util.Objects;
import java.util.Optional;
import org.bukkit.inventory.ItemStack;

/**
 * Immutable persistent drawer state.
 *
 * <p>Schema 1 stored only the hidden count, so its total is {@code hidden + physical}. Schema 2
 * stores the authoritative total in {@code hiddenCount} (the retained field name keeps the old
 * PDC key compatible) and records the physical mirror expected by the reconciler. A difference
 * between that expectation and the observed barrel inventory is treated as a pending real hopper
 * delta until reconciliation persists the new total.</p>
 */
public final class DrawerState {

    public static final int LEGACY_SCHEMA_VERSION = 1;
    public static final int CURRENT_SCHEMA_VERSION = 2;

    private final int schemaVersion;
    private final String tierId;
    private final ItemStack template;
    private final long hiddenCount;
    private final long expectedMirrorCount;
    private final long capacitySnapshot;
    private final DrawerProxyJournal proxyJournal;
    private final DrawerDisplayLink displayLink;

    private DrawerState(
            final int schemaVersion,
            final String tierId,
            final ItemStack template,
            final long hiddenCount,
            final long expectedMirrorCount,
            final long capacitySnapshot,
            final DrawerProxyJournal proxyJournal,
            final DrawerDisplayLink displayLink
    ) {
        if (schemaVersion <= 0 || schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw new DrawerValidationException("Unsupported drawer schema version " + schemaVersion);
        }
        if (tierId == null || tierId.isBlank()) {
            throw new DrawerValidationException("Drawer tier id must not be blank");
        }
        DrawerCapacity.requireNonNegative(hiddenCount, "drawer stored count");
        DrawerCapacity.requireNonNegative(expectedMirrorCount, "expected drawer mirror count");
        if (capacitySnapshot <= 0L) {
            throw new DrawerValidationException("Drawer capacity snapshot must be greater than zero");
        }
        if (hiddenCount > capacitySnapshot) {
            throw new DrawerValidationException("Drawer stored count cannot exceed the capacity snapshot");
        }
        if (schemaVersion >= CURRENT_SCHEMA_VERSION && expectedMirrorCount > hiddenCount) {
            throw new DrawerValidationException("Expected drawer mirror count cannot exceed the stored total");
        }

        final ItemStack normalizedTemplate;
        if (template == null) {
            if (hiddenCount > 0L || expectedMirrorCount > 0L || (proxyJournal != null
                    && (proxyJournal.totalBefore() > 0L || proxyJournal.totalAfter() > 0L))) {
                throw new DrawerValidationException("A non-empty drawer requires an item template");
            }
            normalizedTemplate = null;
        } else {
            normalizedTemplate = DrawerItemIdentity.templateOf(template);
        }

        if (proxyJournal != null && (proxyJournal.totalBefore() > capacitySnapshot
                || proxyJournal.totalAfter() > capacitySnapshot)) {
            throw new DrawerValidationException("Journal totals cannot exceed the capacity snapshot");
        }

        this.schemaVersion = schemaVersion;
        this.tierId = tierId;
        this.template = normalizedTemplate;
        this.hiddenCount = hiddenCount;
        this.expectedMirrorCount = expectedMirrorCount;
        this.capacitySnapshot = capacitySnapshot;
        this.proxyJournal = proxyJournal;
        this.displayLink = displayLink == null ? DrawerDisplayLink.none() : displayLink;
    }

    public static DrawerState empty(final String tierId, final long capacitySnapshot) {
        return empty(CURRENT_SCHEMA_VERSION, tierId, capacitySnapshot);
    }

    public static DrawerState empty(final int schemaVersion, final String tierId, final long capacitySnapshot) {
        return new DrawerState(schemaVersion, tierId, null, 0L, 0L, capacitySnapshot, null, DrawerDisplayLink.none());
    }

    /** Restores schema 1 payloads, which intentionally have no expected-mirror field. */
    public static DrawerState restored(
            final int schemaVersion,
            final String tierId,
            final ItemStack template,
            final long hiddenCount,
            final long capacitySnapshot,
            final DrawerProxyJournal proxyJournal,
            final DrawerDisplayLink displayLink
    ) {
        return restored(schemaVersion, tierId, template, hiddenCount, 0L, capacitySnapshot, proxyJournal, displayLink);
    }

    public static DrawerState restored(
            final int schemaVersion,
            final String tierId,
            final ItemStack template,
            final long storedTotal,
            final long expectedMirrorCount,
            final long capacitySnapshot,
            final DrawerProxyJournal proxyJournal,
            final DrawerDisplayLink displayLink
    ) {
        return new DrawerState(
                schemaVersion,
                tierId,
                template,
                storedTotal,
                expectedMirrorCount,
                capacitySnapshot,
                proxyJournal,
                displayLink
        );
    }

    public int schemaVersion() { return this.schemaVersion; }
    public String tierId() { return this.tierId; }
    public boolean usesReservedMirror() { return this.schemaVersion >= CURRENT_SCHEMA_VERSION; }
    public Optional<ItemStack> template() { return this.template == null ? Optional.empty() : Optional.of(this.template.clone()); }

    public ItemStack requireTemplate() {
        if (this.template == null) {
            throw new DrawerInvariantViolationException("The drawer is empty and has no item template");
        }
        return this.template.clone();
    }

    public boolean hasTemplate() { return this.template != null; }
    public boolean matchesTemplate(final ItemStack candidate) { return this.template != null && DrawerItemIdentity.matches(this.template, candidate); }

    /** Schema 1 hidden count; schema 2 authoritative stored total. */
    public long hiddenCount() { return this.hiddenCount; }
    public long storedTotal() { return this.hiddenCount; }
    public long expectedMirrorCount() { return this.expectedMirrorCount; }
    public long capacitySnapshot() { return this.capacitySnapshot; }
    public Optional<DrawerProxyJournal> proxyJournal() { return Optional.ofNullable(this.proxyJournal); }
    public boolean hasPendingProxyJournal() { return this.proxyJournal != null; }
    public DrawerDisplayLink displayLink() { return this.displayLink; }

    public long totalForPhysical(final long physicalCount) {
        DrawerCapacity.requireNonNegative(physicalCount, "physical proxy count");
        if (physicalCount > 0L && this.template == null) {
            throw new DrawerInvariantViolationException("Physical proxy items exist without a drawer template");
        }
        final long total = this.usesReservedMirror()
                ? DrawerCapacity.totalAfterMirrorDelta(
                        this.hiddenCount,
                        this.expectedMirrorCount,
                        physicalCount,
                        this.capacitySnapshot
                )
                : DrawerCapacity.checkedAdd(this.hiddenCount, physicalCount, "Drawer total");
        if (physicalCount > total) {
            throw new DrawerInvariantViolationException("Physical drawer mirror exceeds the stored total");
        }
        if (total > this.capacitySnapshot) {
            throw new DrawerInvariantViolationException("Drawer total exceeds its capacity snapshot");
        }
        return total;
    }

    public long availableCapacityForPhysical(final long physicalCount) {
        return this.capacitySnapshot - this.totalForPhysical(physicalCount);
    }

    public boolean isEmpty(final long physicalCount) { return this.totalForPhysical(physicalCount) == 0L; }

    /** Converts a legacy state using the observed barrel inventory exactly once. */
    public DrawerState migrateToReservedMirror(final long observedPhysicalCount) {
        if (this.usesReservedMirror()) {
            return this;
        }
        final long total = totalForPhysical(observedPhysicalCount);
        return new DrawerState(
                CURRENT_SCHEMA_VERSION, this.tierId, this.template, total, observedPhysicalCount,
                this.capacitySnapshot, this.proxyJournal, this.displayLink
        );
    }

    public DrawerState withHiddenCount(final long newHiddenCount) {
        return copy(this.schemaVersion, this.template, newHiddenCount, this.expectedMirrorCount,
                this.capacitySnapshot, this.proxyJournal, this.displayLink);
    }

    public DrawerState withStoredTotal(final long newStoredTotal) { return withHiddenCount(newStoredTotal); }

    /** Updates the two coupled reserved-mirror values without constructing an invalid intermediate state. */
    public DrawerState withStoredTotalAndExpectedMirrorCount(
            final long newStoredTotal,
            final long newExpectedMirrorCount
    ) {
        return copy(this.schemaVersion, this.template, newStoredTotal, newExpectedMirrorCount,
                this.capacitySnapshot, this.proxyJournal, this.displayLink);
    }

    public DrawerState withExpectedMirrorCount(final long newExpectedMirrorCount) {
        return copy(this.schemaVersion, this.template, this.hiddenCount, newExpectedMirrorCount,
                this.capacitySnapshot, this.proxyJournal, this.displayLink);
    }

    public DrawerState withTemplate(final ItemStack newTemplate) {
        return copy(this.schemaVersion, newTemplate, this.hiddenCount, this.expectedMirrorCount,
                this.capacitySnapshot, this.proxyJournal, this.displayLink);
    }

    public DrawerState withoutTemplate() {
        if (this.hiddenCount != 0L || this.expectedMirrorCount != 0L || (this.proxyJournal != null
                && (this.proxyJournal.totalBefore() != 0L || this.proxyJournal.totalAfter() != 0L))) {
            throw new DrawerInvariantViolationException("Cannot clear a template while the drawer still has stored items");
        }
        return copy(this.schemaVersion, null, 0L, 0L, this.capacitySnapshot, this.proxyJournal, this.displayLink);
    }

    public DrawerState withProxyJournal(final DrawerProxyJournal newJournal) {
        return copy(this.schemaVersion, this.template, this.hiddenCount, this.expectedMirrorCount,
                this.capacitySnapshot, Objects.requireNonNull(newJournal, "newJournal"), this.displayLink);
    }

    public DrawerState withoutProxyJournal() {
        return copy(this.schemaVersion, this.template, this.hiddenCount, this.expectedMirrorCount,
                this.capacitySnapshot, null, this.displayLink);
    }

    public DrawerState withDisplayLink(final DrawerDisplayLink newDisplayLink) {
        return copy(this.schemaVersion, this.template, this.hiddenCount, this.expectedMirrorCount,
                this.capacitySnapshot, this.proxyJournal, newDisplayLink);
    }

    public DrawerState withCapacitySnapshot(final long newCapacitySnapshot) {
        return copy(this.schemaVersion, this.template, this.hiddenCount, this.expectedMirrorCount,
                newCapacitySnapshot, this.proxyJournal, this.displayLink);
    }

    public DrawerState initializeCapacityForFirstItem(final DrawerTier tier, final ItemStack firstItem) {
        Objects.requireNonNull(tier, "tier");
        DrawerItemIdentity.requireStorageCandidate(firstItem, "First drawer item");
        if (!this.tierId.equals(tier.id())) {
            throw new DrawerValidationException("Tier " + tier.id() + " cannot initialize drawer tier " + this.tierId);
        }
        if (this.template != null || this.hiddenCount != 0L || this.expectedMirrorCount != 0L || this.proxyJournal != null) {
            throw new DrawerInvariantViolationException("Only an empty, uninitialized drawer can adopt its first-item capacity");
        }
        return this.withCapacitySnapshot(tier.capacityFor(firstItem));
    }

    private DrawerState copy(
            final int version, final ItemStack newTemplate, final long count, final long expected,
            final long capacity, final DrawerProxyJournal journal, final DrawerDisplayLink link
    ) {
        return new DrawerState(version, this.tierId, newTemplate, count, expected, capacity, journal, link);
    }

    @Override
    public boolean equals(final Object object) {
        if (this == object) return true;
        if (!(object instanceof DrawerState other)) return false;
        return this.schemaVersion == other.schemaVersion && this.hiddenCount == other.hiddenCount
                && this.expectedMirrorCount == other.expectedMirrorCount && this.capacitySnapshot == other.capacitySnapshot
                && this.tierId.equals(other.tierId) && Objects.equals(this.template, other.template)
                && Objects.equals(this.proxyJournal, other.proxyJournal) && this.displayLink.equals(other.displayLink);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.schemaVersion, this.tierId, this.template, this.hiddenCount,
                this.expectedMirrorCount, this.capacitySnapshot, this.proxyJournal, this.displayLink);
    }

    @Override
    public String toString() {
        return "DrawerState[tierId=" + this.tierId + ", storedTotal=" + this.hiddenCount
                + ", expectedMirrorCount=" + this.expectedMirrorCount + ", capacitySnapshot=" + this.capacitySnapshot
                + ", hasTemplate=" + (this.template != null) + ", journal=" + (this.proxyJournal != null) + "]";
    }
}
