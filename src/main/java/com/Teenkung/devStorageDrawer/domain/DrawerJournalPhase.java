package com.Teenkung.devStorageDrawer.domain;

/** Durable phase used to resolve hidden-only withdrawals after a restart. */
public enum DrawerJournalPhase {
    PREPARED,
    APPLIED
}
