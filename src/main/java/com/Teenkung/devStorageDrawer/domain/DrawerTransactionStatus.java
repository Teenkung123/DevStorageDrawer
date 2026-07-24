package com.Teenkung.devStorageDrawer.domain;

/** A non-exceptional reason that a requested transfer was not accepted. */
public enum DrawerTransactionStatus {
    APPLIED,
    EMPTY,
    FULL,
    ITEM_MISMATCH,
    INVALID_REQUEST
}
