package com.teenkung.devstoragedrawer.api;

/** Outcome of an external logical item transfer. */
public enum DrawerTransferStatus {
    APPLIED,
    CANCELLED,
    NOT_DRAWER,
    WORLD_UNAVAILABLE,
    UNAVAILABLE,
    RECOVERING,
    CORRUPT,
    EMPTY,
    FULL,
    ITEM_MISMATCH,
    INVALID_REQUEST,
    PERSISTENCE_FAILURE,
    PLUGIN_DISABLED
}
