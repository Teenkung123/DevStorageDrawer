package com.Teenkung.devStorageDrawer.persistence;

import com.Teenkung.devStorageDrawer.domain.DrawerDisplayLink;
import com.Teenkung.devStorageDrawer.domain.DrawerJournalKind;
import com.Teenkung.devStorageDrawer.domain.DrawerJournalPhase;
import com.Teenkung.devStorageDrawer.domain.DrawerProxyJournal;
import com.Teenkung.devStorageDrawer.domain.DrawerState;
import com.Teenkung.devStorageDrawer.domain.DrawerValidationException;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** PDC adapter for the durable drawer state format. */
public final class DrawerPdcCodec {

    private static final byte MARKER_VALUE = 1;

    private final DrawerStateCodec stateCodec;

    public DrawerPdcCodec() {
        this(new DrawerStateCodec());
    }

    DrawerPdcCodec(final DrawerStateCodec stateCodec) {
        this.stateCodec = stateCodec;
    }

    public boolean isDrawer(final PersistentDataContainer container) {
        return container.has(DrawerPdcKeys.DRAWER_MARKER, PersistentDataType.BYTE);
    }

    public DrawerStateReadResult read(final PersistentDataContainer container) {
        final Byte marker = container.get(DrawerPdcKeys.DRAWER_MARKER, PersistentDataType.BYTE);
        if (marker == null) {
            return new DrawerStateReadResult.Absent();
        }
        if (marker != MARKER_VALUE) {
            return new DrawerStateReadResult.Corrupt("Unsupported drawer marker value " + marker);
        }

        try {
            final Integer schemaVersion = required(container, DrawerPdcKeys.SCHEMA_VERSION, PersistentDataType.INTEGER, "schema version");
            final String tierId = required(container, DrawerPdcKeys.TIER_ID, PersistentDataType.STRING, "tier id");
            final Long hiddenCount = required(container, DrawerPdcKeys.HIDDEN_COUNT, PersistentDataType.LONG, "hidden count");
            final Long expectedMirrorCount = schemaVersion >= DrawerState.CURRENT_SCHEMA_VERSION
                    ? required(container, DrawerPdcKeys.EXPECTED_MIRROR_COUNT, PersistentDataType.LONG, "expected mirror count")
                    : 0L;
            final Long capacitySnapshot = required(
                    container,
                    DrawerPdcKeys.CAPACITY_SNAPSHOT,
                    PersistentDataType.LONG,
                    "capacity snapshot"
            );
            final byte[] templateBytes = container.get(DrawerPdcKeys.TEMPLATE, PersistentDataType.BYTE_ARRAY);
            final DrawerProxyJournal journal = readJournal(container);
            final DrawerDisplayLink displayLink = new DrawerDisplayLink(
                    readUuid(container, DrawerPdcKeys.JAVA_ITEM_DISPLAY_ID),
                    readUuid(container, DrawerPdcKeys.JAVA_NAME_TEXT_DISPLAY_ID),
                    readUuid(container, DrawerPdcKeys.JAVA_AMOUNT_TEXT_DISPLAY_ID),
                    readUuid(container, DrawerPdcKeys.BEDROCK_ITEM_DISPLAY_ID),
                    readUuid(container, DrawerPdcKeys.BEDROCK_TEXT_DISPLAY_ID)
            );
            final DrawerState state = this.stateCodec.decode(new DrawerStatePayload(
                    schemaVersion,
                    tierId,
                    templateBytes,
                    hiddenCount,
                    expectedMirrorCount,
                    capacitySnapshot,
                    journal,
                    displayLink
            ));
            return new DrawerStateReadResult.Valid(state);
        } catch (final IllegalArgumentException exception) {
            return new DrawerStateReadResult.Corrupt(exception.getMessage(), exception);
        }
    }

    /**
     * Writes to the supplied PDC but deliberately does not call {@code BlockState#update}; callers
     * must save the barrel snapshot on its owning region thread after any paired inventory change.
     */
    public void write(final PersistentDataContainer container, final DrawerState state) {
        final DrawerStatePayload payload = this.stateCodec.encode(state);
        clear(container);

        container.set(DrawerPdcKeys.SCHEMA_VERSION, PersistentDataType.INTEGER, payload.schemaVersion());
        container.set(DrawerPdcKeys.TIER_ID, PersistentDataType.STRING, payload.tierId());
        final byte[] templateBytes = payload.templateBytes();
        if (templateBytes != null) {
            container.set(DrawerPdcKeys.TEMPLATE, PersistentDataType.BYTE_ARRAY, templateBytes);
        }
        container.set(DrawerPdcKeys.HIDDEN_COUNT, PersistentDataType.LONG, payload.hiddenCount());
        if (payload.schemaVersion() >= DrawerState.CURRENT_SCHEMA_VERSION) {
            container.set(DrawerPdcKeys.EXPECTED_MIRROR_COUNT, PersistentDataType.LONG, payload.expectedMirrorCount());
        }
        container.set(DrawerPdcKeys.CAPACITY_SNAPSHOT, PersistentDataType.LONG, payload.capacitySnapshot());
        writeJournal(container, payload.proxyJournal());
        writeUuid(container, DrawerPdcKeys.JAVA_ITEM_DISPLAY_ID, payload.displayLink().javaItemDisplayId());
        writeUuid(container, DrawerPdcKeys.JAVA_NAME_TEXT_DISPLAY_ID, payload.displayLink().javaNameTextDisplayId());
        writeUuid(container, DrawerPdcKeys.JAVA_AMOUNT_TEXT_DISPLAY_ID, payload.displayLink().javaAmountTextDisplayId());
        writeUuid(container, DrawerPdcKeys.BEDROCK_ITEM_DISPLAY_ID, payload.displayLink().bedrockItemDisplayId());
        writeUuid(container, DrawerPdcKeys.BEDROCK_TEXT_DISPLAY_ID, payload.displayLink().bedrockTextDisplayId());

        // Write the marker last so a partially populated snapshot is never treated as a valid drawer.
        container.set(DrawerPdcKeys.DRAWER_MARKER, PersistentDataType.BYTE, MARKER_VALUE);
    }

    public void clear(final PersistentDataContainer container) {
        container.remove(DrawerPdcKeys.DRAWER_MARKER);
        container.remove(DrawerPdcKeys.SCHEMA_VERSION);
        container.remove(DrawerPdcKeys.TIER_ID);
        container.remove(DrawerPdcKeys.TEMPLATE);
        container.remove(DrawerPdcKeys.HIDDEN_COUNT);
        container.remove(DrawerPdcKeys.EXPECTED_MIRROR_COUNT);
        container.remove(DrawerPdcKeys.CAPACITY_SNAPSHOT);
        container.remove(DrawerPdcKeys.JOURNAL_OPERATION_ID);
        container.remove(DrawerPdcKeys.JOURNAL_KIND);
        container.remove(DrawerPdcKeys.JOURNAL_PHASE);
        container.remove(DrawerPdcKeys.JOURNAL_OWNER_ID);
        container.remove(DrawerPdcKeys.JOURNAL_TOTAL_COUNT);
        container.remove(DrawerPdcKeys.JOURNAL_TOTAL_AFTER);
        container.remove(DrawerPdcKeys.JOURNAL_PHYSICAL_BEFORE);
        container.remove(DrawerPdcKeys.JOURNAL_PHYSICAL_TARGET);
        container.remove(DrawerPdcKeys.JOURNAL_CREATED_AT);
        container.remove(DrawerPdcKeys.JAVA_ITEM_DISPLAY_ID);
        container.remove(DrawerPdcKeys.JAVA_NAME_TEXT_DISPLAY_ID);
        container.remove(DrawerPdcKeys.JAVA_AMOUNT_TEXT_DISPLAY_ID);
        container.remove(DrawerPdcKeys.BEDROCK_ITEM_DISPLAY_ID);
        container.remove(DrawerPdcKeys.BEDROCK_TEXT_DISPLAY_ID);
    }

    private static DrawerProxyJournal readJournal(final PersistentDataContainer container) {
        final String operationId = container.get(DrawerPdcKeys.JOURNAL_OPERATION_ID, PersistentDataType.STRING);
        final String kind = container.get(DrawerPdcKeys.JOURNAL_KIND, PersistentDataType.STRING);
        final String phase = container.get(DrawerPdcKeys.JOURNAL_PHASE, PersistentDataType.STRING);
        final String ownerId = container.get(DrawerPdcKeys.JOURNAL_OWNER_ID, PersistentDataType.STRING);
        final Long totalBefore = container.get(DrawerPdcKeys.JOURNAL_TOTAL_COUNT, PersistentDataType.LONG);
        final Long totalAfter = container.get(DrawerPdcKeys.JOURNAL_TOTAL_AFTER, PersistentDataType.LONG);
        final Long physicalBefore = container.get(DrawerPdcKeys.JOURNAL_PHYSICAL_BEFORE, PersistentDataType.LONG);
        final Long physicalTarget = container.get(DrawerPdcKeys.JOURNAL_PHYSICAL_TARGET, PersistentDataType.LONG);
        final Long createdAt = container.get(DrawerPdcKeys.JOURNAL_CREATED_AT, PersistentDataType.LONG);
        if (operationId == null && kind == null && phase == null && ownerId == null && totalBefore == null && totalAfter == null
                && physicalBefore == null && physicalTarget == null && createdAt == null) {
            return null;
        }
        if (operationId == null || totalBefore == null || physicalBefore == null || physicalTarget == null || createdAt == null) {
            throw new DrawerValidationException("Drawer proxy journal is incomplete");
        }
        try {
            // PDC written before the transfer-journal extension did not have total-after. It was
            // always a same-total proxy rebalance, so retain that behavior on read.
            return new DrawerProxyJournal(
                    UUID.fromString(operationId),
                    kind == null ? DrawerJournalKind.PROXY_REBALANCE : DrawerJournalKind.valueOf(kind),
                    phase == null ? DrawerJournalPhase.PREPARED : DrawerJournalPhase.valueOf(phase),
                    ownerId == null ? null : UUID.fromString(ownerId),
                    totalBefore,
                    totalAfter == null ? totalBefore : totalAfter,
                    physicalBefore,
                    physicalTarget,
                    createdAt
            );
        } catch (final IllegalArgumentException exception) {
            throw new DrawerValidationException("Drawer proxy journal operation id is invalid", exception);
        }
    }

    private static void writeJournal(final PersistentDataContainer container, final DrawerProxyJournal journal) {
        if (journal == null) {
            return;
        }
        container.set(DrawerPdcKeys.JOURNAL_OPERATION_ID, PersistentDataType.STRING, journal.operationId().toString());
        container.set(DrawerPdcKeys.JOURNAL_KIND, PersistentDataType.STRING, journal.kind().name());
        container.set(DrawerPdcKeys.JOURNAL_PHASE, PersistentDataType.STRING, journal.phase().name());
        if (journal.ownerId() != null) {
            container.set(DrawerPdcKeys.JOURNAL_OWNER_ID, PersistentDataType.STRING, journal.ownerId().toString());
        }
        container.set(DrawerPdcKeys.JOURNAL_TOTAL_COUNT, PersistentDataType.LONG, journal.totalBefore());
        container.set(DrawerPdcKeys.JOURNAL_TOTAL_AFTER, PersistentDataType.LONG, journal.totalAfter());
        container.set(DrawerPdcKeys.JOURNAL_PHYSICAL_BEFORE, PersistentDataType.LONG, journal.physicalBefore());
        container.set(DrawerPdcKeys.JOURNAL_PHYSICAL_TARGET, PersistentDataType.LONG, journal.physicalTarget());
        container.set(DrawerPdcKeys.JOURNAL_CREATED_AT, PersistentDataType.LONG, journal.createdAtEpochMillis());
    }

    private static UUID readUuid(final PersistentDataContainer container, final NamespacedKey key) {
        final String raw = container.get(key, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (final IllegalArgumentException exception) {
            throw new DrawerValidationException("Drawer display id " + key.getKey() + " is not a UUID", exception);
        }
    }

    private static void writeUuid(final PersistentDataContainer container, final NamespacedKey key, final UUID value) {
        if (value != null) {
            container.set(key, PersistentDataType.STRING, value.toString());
        }
    }

    private static <P, C> C required(
            final PersistentDataContainer container,
            final NamespacedKey key,
            final PersistentDataType<P, C> type,
            final String label
    ) {
        final C value = container.get(key, type);
        if (value == null) {
            throw new DrawerValidationException("Drawer " + label + " is missing");
        }
        return value;
    }
}
