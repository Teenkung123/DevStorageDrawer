package com.teenkung.devstoragedrawer.persistence;

import com.teenkung.devstoragedrawer.domain.DrawerState;
import java.util.Objects;

/**
 * Avoids treating corrupt tagged barrels as empty drawers. Callers should quarantine/report a
 * {@link Corrupt} result and never silently overwrite it.
 */
public sealed interface DrawerStateReadResult permits DrawerStateReadResult.Absent, DrawerStateReadResult.Valid, DrawerStateReadResult.Corrupt {

    record Absent() implements DrawerStateReadResult {
    }

    record Valid(DrawerState state) implements DrawerStateReadResult {
        public Valid {
            Objects.requireNonNull(state, "state");
        }
    }

    record Corrupt(String reason, Throwable cause) implements DrawerStateReadResult {
        public Corrupt {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("A corrupt state result needs a reason");
            }
        }

        public Corrupt(final String reason) {
            this(reason, null);
        }
    }
}
