package com.teenkung.devstoragedrawer.api;

/** Outcome of creating a tagged drawer tier item through the API. */
public enum DrawerTierItemStatus {
    CREATED,
    UNKNOWN_TIER,
    INVALID_AMOUNT,
    PLUGIN_DISABLED
}
