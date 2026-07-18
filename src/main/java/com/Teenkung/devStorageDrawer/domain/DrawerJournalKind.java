package com.teenkung.devstoragedrawer.domain;

/** Identifies which recovery protocol owns a pending drawer journal. */
public enum DrawerJournalKind {
    PROXY_REBALANCE,
    PLAYER_WITHDRAWAL,
    EXTERNAL_SALE
}
