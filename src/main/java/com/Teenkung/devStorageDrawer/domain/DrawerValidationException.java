package com.teenkung.devstoragedrawer.domain;

/**
 * Thrown when persisted or configuration-backed drawer data cannot safely describe a drawer.
 */
public final class DrawerValidationException extends IllegalArgumentException {

    public DrawerValidationException(final String message) {
        super(message);
    }

    public DrawerValidationException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
