package com.Teenkung.devStorageDrawer.domain;

/** Identifies which recovery protocol owns a pending drawer journal. */
public enum DrawerJournalKind {
    PROXY_REBALANCE,
    PLAYER_WITHDRAWAL,
    OVERFLOW_RECOVERY,
    EXTERNAL_SALE
}
