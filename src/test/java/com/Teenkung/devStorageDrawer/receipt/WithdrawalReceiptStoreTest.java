package com.Teenkung.devStorageDrawer.receipt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WithdrawalReceiptStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsStatusAndRemovalAtomically() throws IOException {
        final Path file = temporaryDirectory.resolve("receipts.dat");
        final WithdrawalReceipt receipt = receipt();
        final WithdrawalReceiptStore writer = new WithdrawalReceiptStore(file);
        writer.prepare(receipt);
        writer.markDeliverable(receipt.operationId());

        final WithdrawalReceiptStore reader = new WithdrawalReceiptStore(file);
        reader.load();
        final WithdrawalReceipt loaded = reader.find(receipt.operationId()).orElseThrow();
        assertEquals(WithdrawalReceiptStatus.DELIVERABLE, loaded.status());
        assertEquals(receipt.ownerId(), loaded.ownerId());
        assertArrayEquals(receipt.templateBytes(), loaded.templateBytes());

        reader.remove(receipt.operationId());
        final WithdrawalReceiptStore empty = new WithdrawalReceiptStore(file);
        empty.load();
        assertEquals(0, empty.snapshot().size());
    }

    @Test
    void rejectsTruncatedLedgerWithoutDiscardingIt() throws IOException {
        final Path file = temporaryDirectory.resolve("receipts.dat");
        final WithdrawalReceiptStore store = new WithdrawalReceiptStore(file);
        store.prepare(receipt());
        final byte[] complete = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(complete, complete.length - 3));

        final WithdrawalReceiptStore corrupt = new WithdrawalReceiptStore(file);
        assertThrows(IOException.class, corrupt::load);
        assertEquals(complete.length - 3, Files.size(file));
    }

    private static WithdrawalReceipt receipt() {
        return new WithdrawalReceipt(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                2,
                3,
                new byte[] {4, 5, 6},
                64L,
                WithdrawalReceiptStatus.PREPARED,
                123L
        );
    }
}
