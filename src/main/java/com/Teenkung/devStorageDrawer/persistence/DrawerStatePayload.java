package com.teenkung.devstoragedrawer.persistence;

import com.teenkung.devstoragedrawer.domain.DrawerDisplayLink;
import com.teenkung.devstoragedrawer.domain.DrawerProxyJournal;
import java.util.Arrays;
import java.util.Objects;

/**
 * PDC-neutral primitive representation. Keeping this separate lets tests verify the ItemStack
 * byte codec without requiring a running Bukkit server or a mock PersistentDataContainer.
 */
public final class DrawerStatePayload {

    private final int schemaVersion;
    private final String tierId;
    private final byte[] templateBytes;
    private final long hiddenCount;
    private final long expectedMirrorCount;
    private final long capacitySnapshot;
    private final DrawerProxyJournal proxyJournal;
    private final DrawerDisplayLink displayLink;

    public DrawerStatePayload(
            final int schemaVersion,
            final String tierId,
            final byte[] templateBytes,
            final long hiddenCount,
            final long capacitySnapshot,
            final DrawerProxyJournal proxyJournal,
            final DrawerDisplayLink displayLink
    ) {
        this(schemaVersion, tierId, templateBytes, hiddenCount, 0L, capacitySnapshot, proxyJournal, displayLink);
    }

    public DrawerStatePayload(
            final int schemaVersion,
            final String tierId,
            final byte[] templateBytes,
            final long hiddenCount,
            final long expectedMirrorCount,
            final long capacitySnapshot,
            final DrawerProxyJournal proxyJournal,
            final DrawerDisplayLink displayLink
    ) {
        this.schemaVersion = schemaVersion;
        this.tierId = Objects.requireNonNull(tierId, "tierId");
        this.templateBytes = templateBytes == null ? null : templateBytes.clone();
        this.hiddenCount = hiddenCount;
        this.expectedMirrorCount = expectedMirrorCount;
        this.capacitySnapshot = capacitySnapshot;
        this.proxyJournal = proxyJournal;
        this.displayLink = displayLink == null ? DrawerDisplayLink.none() : displayLink;
    }

    public int schemaVersion() {
        return this.schemaVersion;
    }

    public String tierId() {
        return this.tierId;
    }

    public byte[] templateBytes() {
        return this.templateBytes == null ? null : this.templateBytes.clone();
    }

    public long hiddenCount() {
        return this.hiddenCount;
    }

    public long expectedMirrorCount() {
        return this.expectedMirrorCount;
    }

    public long capacitySnapshot() {
        return this.capacitySnapshot;
    }

    public DrawerProxyJournal proxyJournal() {
        return this.proxyJournal;
    }

    public DrawerDisplayLink displayLink() {
        return this.displayLink;
    }

    @Override
    public boolean equals(final Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof DrawerStatePayload other)) {
            return false;
        }
        return this.schemaVersion == other.schemaVersion
                && this.hiddenCount == other.hiddenCount
                && this.expectedMirrorCount == other.expectedMirrorCount
                && this.capacitySnapshot == other.capacitySnapshot
                && this.tierId.equals(other.tierId)
                && Arrays.equals(this.templateBytes, other.templateBytes)
                && Objects.equals(this.proxyJournal, other.proxyJournal)
                && this.displayLink.equals(other.displayLink);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                this.schemaVersion,
                this.tierId,
                this.hiddenCount,
                this.expectedMirrorCount,
                this.capacitySnapshot,
                this.proxyJournal,
                this.displayLink
        );
        result = 31 * result + Arrays.hashCode(this.templateBytes);
        return result;
    }
}
