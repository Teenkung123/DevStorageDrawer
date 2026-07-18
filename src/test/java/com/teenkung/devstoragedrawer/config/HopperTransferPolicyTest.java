package com.teenkung.devstoragedrawer.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HopperTransferPolicyTest {

    @Test
    void appliesWorldOverrideAndCapsBatchToSourceAndItemStackLimits() {
        final HopperTransferPolicy policy = new HopperTransferPolicy(16, Map.of("slow_world", 4));

        assertEquals(16, policy.guardedAmountFor("world", 64, 64));
        assertEquals(10, policy.guardedAmountFor("world", 10, 64));
        assertEquals(1, policy.guardedAmountFor("world", 64, 1));
        assertEquals(4, policy.guardedAmountFor("slow_world", 64, 64));
    }

    @Test
    void copiesAndValidatesWorldOverrides() {
        final Map<String, Integer> source = new HashMap<>();
        source.put("world", 16);
        final HopperTransferPolicy policy = new HopperTransferPolicy(1, source);
        source.put("world", 32);

        assertEquals(16, policy.amountFor("world"));
        assertThrows(IllegalArgumentException.class, () -> new HopperTransferPolicy(0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new HopperTransferPolicy(1, Map.of("world", 0)));
        assertThrows(IllegalArgumentException.class, () -> policy.guardedAmountFor("world", 0, 64));
    }
}
