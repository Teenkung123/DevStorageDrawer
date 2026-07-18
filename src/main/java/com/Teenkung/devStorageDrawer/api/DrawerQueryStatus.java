package com.teenkung.devstoragedrawer.api;

/** Outcome of a logical drawer lookup. */
public enum DrawerQueryStatus {
    FOUND,
    NOT_DRAWER,
    WORLD_UNAVAILABLE,
    UNAVAILABLE,
    RECOVERING,
    CORRUPT,
    PLUGIN_DISABLED
}
