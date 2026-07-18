package com.teenkung.devstoragedrawer.domain;

/** The source of a storage mutation, used to enforce its total-count invariant. */
public enum DrawerOperation {
    PLAYER_DEPOSIT,
    PLAYER_WITHDRAW,
    PROXY_INSERT,
    PROXY_EXTRACT
}
