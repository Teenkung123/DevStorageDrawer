package com.Teenkung.devStorageDrawer.receipt;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Bukkit-free receipt payload safe to read and write on the async scheduler. */
public record WithdrawalReceipt(
        UUID operationId,
        UUID ownerId,
        UUID worldId,
        int blockX,
        int blockY,
        int blockZ,
        byte[] templateBytes,
        long count,
        WithdrawalReceiptStatus status,
        long createdAtEpochMillis
) {
    private static final int MAX_TEMPLATE_BYTES = 4 * 1024 * 1024;

    public WithdrawalReceipt {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(templateBytes, "templateBytes");
        Objects.requireNonNull(status, "status");
        if (templateBytes.length < 1 || templateBytes.length > MAX_TEMPLATE_BYTES) {
            throw new IllegalArgumentException("Withdrawal receipt template length is invalid");
        }
        if (count < 1L || createdAtEpochMillis < 0L) {
            throw new IllegalArgumentException("Withdrawal receipt count and timestamp must be valid");
        }
        templateBytes = templateBytes.clone();
    }

    @Override
    public byte[] templateBytes() {
        return this.templateBytes.clone();
    }

    public WithdrawalReceipt withStatus(final WithdrawalReceiptStatus replacement) {
        return new WithdrawalReceipt(
                this.operationId,
                this.ownerId,
                this.worldId,
                this.blockX,
                this.blockY,
                this.blockZ,
                this.templateBytes,
                this.count,
                replacement,
                this.createdAtEpochMillis
        );
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof WithdrawalReceipt receipt
                && this.blockX == receipt.blockX
                && this.blockY == receipt.blockY
                && this.blockZ == receipt.blockZ
                && this.count == receipt.count
                && this.createdAtEpochMillis == receipt.createdAtEpochMillis
                && this.operationId.equals(receipt.operationId)
                && this.ownerId.equals(receipt.ownerId)
                && this.worldId.equals(receipt.worldId)
                && Arrays.equals(this.templateBytes, receipt.templateBytes)
                && this.status == receipt.status;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                this.operationId,
                this.ownerId,
                this.worldId,
                this.blockX,
                this.blockY,
                this.blockZ,
                this.count,
                this.status,
                this.createdAtEpochMillis
        );
        result = 31 * result + Arrays.hashCode(this.templateBytes);
        return result;
    }
}
