package com.teenkung.devstoragedrawer.persistence;

import org.bukkit.NamespacedKey;

/** Stable PDC field names. These are data-format identifiers, not display/configuration keys. */
public final class DrawerPdcKeys {

    public static final String NAMESPACE = "devstoragedrawer";

    public static final NamespacedKey DRAWER_MARKER = key("drawer");
    public static final NamespacedKey SCHEMA_VERSION = key("schema_version");
    public static final NamespacedKey TIER_ID = key("tier_id");
    public static final NamespacedKey TEMPLATE = key("template");
    public static final NamespacedKey HIDDEN_COUNT = key("hidden_count");
    /** Schema 2 only: expected count in the real barrel mirror. */
    public static final NamespacedKey EXPECTED_MIRROR_COUNT = key("expected_mirror_count");
    public static final NamespacedKey CAPACITY_SNAPSHOT = key("capacity_snapshot");

    public static final NamespacedKey JOURNAL_OPERATION_ID = key("journal_operation_id");
    public static final NamespacedKey JOURNAL_KIND = key("journal_kind");
    public static final NamespacedKey JOURNAL_PHASE = key("journal_phase");
    public static final NamespacedKey JOURNAL_OWNER_ID = key("journal_owner_id");
    public static final NamespacedKey JOURNAL_TOTAL_COUNT = key("journal_total_count");
    public static final NamespacedKey JOURNAL_TOTAL_AFTER = key("journal_total_after");
    public static final NamespacedKey JOURNAL_PHYSICAL_BEFORE = key("journal_physical_before");
    public static final NamespacedKey JOURNAL_PHYSICAL_TARGET = key("journal_physical_target");
    public static final NamespacedKey JOURNAL_CREATED_AT = key("journal_created_at");

    public static final NamespacedKey JAVA_ITEM_DISPLAY_ID = key("java_item_display_id");
    public static final NamespacedKey JAVA_NAME_TEXT_DISPLAY_ID = key("java_name_text_display_id");
    public static final NamespacedKey JAVA_AMOUNT_TEXT_DISPLAY_ID = key("java_amount_text_display_id");
    public static final NamespacedKey BEDROCK_ITEM_DISPLAY_ID = key("bedrock_item_display_id");
    public static final NamespacedKey BEDROCK_TEXT_DISPLAY_ID = key("bedrock_text_display_id");

    private DrawerPdcKeys() {
    }

    private static NamespacedKey key(final String value) {
        return new NamespacedKey(NAMESPACE, value);
    }
}
