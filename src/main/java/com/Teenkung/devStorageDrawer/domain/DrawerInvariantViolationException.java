package com.Teenkung.devStorageDrawer.domain;

/**
 * Signals an impossible storage state. Callers must reconcile or quarantine the drawer instead
 * of trying to repair it by guessing, because guessing can duplicate or destroy items.
 */
public final class DrawerInvariantViolationException extends IllegalStateException {

    public DrawerInvariantViolationException(final String message) {
        super(message);
    }

    public DrawerInvariantViolationException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
