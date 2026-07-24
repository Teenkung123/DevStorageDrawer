package com.Teenkung.devStorageDrawer.api;

/** Reason reported with a committed drawer-state change. */
public enum DrawerChangeCause {
    PLACED,
    PLAYER_DEPOSIT,
    PLAYER_WITHDRAW,
    AUTOMATION,
    RECOVERY,
    MIGRATION,
    EXTERNAL_SALE,
    API_DEPOSIT,
    API_WITHDRAW,
    SYSTEM
}
