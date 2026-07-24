package com.Teenkung.devStorageDrawer.domain;

import org.bukkit.inventory.ItemStack;

/**
 * Storage abstraction retained for future multi-item and compacting drawers. V1's implementation
 * stores exactly one item identity and keeps barrel proxy stacks separate from hidden stock.
 */
public interface DrawerStorageStrategy {

    DrawerStorageTransaction deposit(DrawerState state, long physicalProxyCount, ItemStack offered, long requestedAmount);

    DrawerStorageTransaction withdraw(DrawerState state, long physicalProxyCount, long requestedAmount);

    DrawerStorageTransaction acceptProxyInsert(
            DrawerState state,
            long physicalProxyCount,
            ItemStack offered,
            long requestedAmount
    );

    DrawerStorageTransaction permitProxyExtract(
            DrawerState state,
            long physicalProxyCount,
            ItemStack offered,
            long requestedAmount
    );

    DrawerRebalancePlan prepareRebalance(DrawerState state, long physicalProxyCount, int outputProxySlots);

    DrawerJournalReconciliation reconcile(DrawerState state, long observedPhysicalProxyCount);
}
