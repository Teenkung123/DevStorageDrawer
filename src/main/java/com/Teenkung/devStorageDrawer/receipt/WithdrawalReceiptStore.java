package com.Teenkung.devStorageDrawer.receipt;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Atomic, fsync-backed receipt ledger. Mutating methods must run on the async scheduler. */
public final class WithdrawalReceiptStore {
    private static final int MAGIC = 0x44534452;
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_RECORDS = 100_000;
    private static final int MAX_TEMPLATE_BYTES = 4 * 1024 * 1024;

    private final Path file;
    private final Map<UUID, WithdrawalReceipt> receipts = new LinkedHashMap<>();

    public WithdrawalReceiptStore(final Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    }

    /** Loads the complete ledger before listeners are registered. */
    public synchronized void load() throws IOException {
        receipts.clear();
        if (!Files.exists(file)) {
            return;
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Withdrawal receipt ledger has an invalid header");
            }
            final int version = input.readInt();
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported withdrawal receipt ledger version " + version);
            }
            final int count = input.readInt();
            if (count < 0 || count > MAX_RECORDS) {
                throw new IOException("Withdrawal receipt ledger record count is invalid");
            }
            for (int index = 0; index < count; index++) {
                final WithdrawalReceipt receipt = read(input);
                if (receipts.put(receipt.operationId(), receipt) != null) {
                    throw new IOException("Withdrawal receipt ledger contains a duplicate operation id");
                }
            }
            if (input.read() != -1) {
                throw new IOException("Withdrawal receipt ledger has trailing data");
            }
        } catch (final EOFException exception) {
            throw new IOException("Withdrawal receipt ledger is truncated", exception);
        } catch (final IllegalArgumentException exception) {
            throw new IOException("Withdrawal receipt ledger contains invalid data", exception);
        }
    }

    public synchronized Optional<WithdrawalReceipt> find(final UUID operationId) {
        return Optional.ofNullable(receipts.get(operationId));
    }

    public synchronized List<WithdrawalReceipt> forOwner(final UUID ownerId) {
        return receipts.values().stream().filter(receipt -> receipt.ownerId().equals(ownerId)).toList();
    }

    public synchronized List<WithdrawalReceipt> snapshot() {
        return List.copyOf(receipts.values());
    }

    public synchronized void prepare(final WithdrawalReceipt receipt) throws IOException {
        Objects.requireNonNull(receipt, "receipt");
        final WithdrawalReceipt existing = receipts.get(receipt.operationId());
        if (existing != null) {
            if (!existing.equals(receipt)) {
                throw new IOException("Withdrawal receipt operation id already has different data");
            }
            return;
        }
        mutateAndPersist(() -> receipts.put(receipt.operationId(), receipt));
    }

    public synchronized void markDeliverable(final UUID operationId) throws IOException {
        final WithdrawalReceipt existing = receipts.get(operationId);
        if (existing == null) {
            throw new IOException("Cannot mark a missing withdrawal receipt deliverable");
        }
        if (existing.status() == WithdrawalReceiptStatus.DELIVERABLE) {
            return;
        }
        mutateAndPersist(() -> receipts.put(operationId, existing.withStatus(WithdrawalReceiptStatus.DELIVERABLE)));
    }

    public synchronized void remove(final UUID operationId) throws IOException {
        removeAll(List.of(operationId));
    }

    public synchronized void removeAll(final Collection<UUID> operationIds) throws IOException {
        final List<UUID> present = operationIds.stream().filter(receipts::containsKey).distinct().toList();
        if (present.isEmpty()) {
            return;
        }
        mutateAndPersist(() -> present.forEach(receipts::remove));
    }

    private void mutateAndPersist(final Runnable mutation) throws IOException {
        final Map<UUID, WithdrawalReceipt> before = new LinkedHashMap<>(receipts);
        mutation.run();
        try {
            persist();
        } catch (final IOException exception) {
            receipts.clear();
            receipts.putAll(before);
            throw exception;
        }
    }

    private void persist() throws IOException {
        Files.createDirectories(file.getParent());
        final Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        ); DataOutputStream output = new DataOutputStream(Channels.newOutputStream(channel))) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeInt(receipts.size());
            for (final WithdrawalReceipt receipt : receipts.values()) {
                write(output, receipt);
            }
            output.flush();
            channel.force(true);
        }
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException exception) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static WithdrawalReceipt read(final DataInputStream input) throws IOException {
        final UUID operationId = readUuid(input);
        final UUID ownerId = readUuid(input);
        final UUID worldId = readUuid(input);
        final int blockX = input.readInt();
        final int blockY = input.readInt();
        final int blockZ = input.readInt();
        final int templateLength = input.readInt();
        if (templateLength < 1 || templateLength > MAX_TEMPLATE_BYTES) {
            throw new IOException("Withdrawal receipt template length is invalid");
        }
        final byte[] templateBytes = input.readNBytes(templateLength);
        if (templateBytes.length != templateLength) {
            throw new EOFException("Withdrawal receipt template is truncated");
        }
        final long count = input.readLong();
        final int statusOrdinal = input.readUnsignedByte();
        if (statusOrdinal >= WithdrawalReceiptStatus.values().length) {
            throw new IOException("Withdrawal receipt status is invalid");
        }
        final long createdAt = input.readLong();
        return new WithdrawalReceipt(
                operationId,
                ownerId,
                worldId,
                blockX,
                blockY,
                blockZ,
                templateBytes,
                count,
                WithdrawalReceiptStatus.values()[statusOrdinal],
                createdAt
        );
    }

    private static void write(final DataOutputStream output, final WithdrawalReceipt receipt) throws IOException {
        writeUuid(output, receipt.operationId());
        writeUuid(output, receipt.ownerId());
        writeUuid(output, receipt.worldId());
        output.writeInt(receipt.blockX());
        output.writeInt(receipt.blockY());
        output.writeInt(receipt.blockZ());
        final byte[] template = receipt.templateBytes();
        output.writeInt(template.length);
        output.write(template);
        output.writeLong(receipt.count());
        output.writeByte(receipt.status().ordinal());
        output.writeLong(receipt.createdAtEpochMillis());
    }

    private static UUID readUuid(final DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeUuid(final DataOutputStream output, final UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }
}
