package com.Teenkung.devStorageDrawer.api;

import java.util.concurrent.CompletableFuture;
import org.bukkit.inventory.ItemStack;

/**
 * Stable, Folia-safe entrypoint for integrations with DevStorageDrawer.
 *
 * <p>Every method completes its future after routing work to the appropriate scheduler. Callers
 * must not block a server thread with {@link CompletableFuture#join()} or {@code get()}; compose
 * the returned future instead. Item stacks passed to and returned from this API are defensively
 * copied, and this API never changes a caller-owned inventory or stack.</p>
 */
public interface DevStorageDrawerApi {

    /** Semantic version of this public API contract. */
    String VERSION = "1.0.0";

    /** Returns the semantic version implemented by this service instance. */
    default String apiVersion() {
        return VERSION;
    }

    /** Looks up the current logical state of a block without exposing internal barrel state. */
    CompletableFuture<DrawerQueryResult> query(DrawerLocation location);

    /**
     * Offers up to {@code requestedAmount} of {@code offered} to a drawer.
     *
     * <p>This method does not and cannot atomically change another plugin's inventory, economy,
     * or database. The calling plugin must move the offered items into its own durable escrow
     * before invoking this method, and must use its own operation journal and compensation policy
     * if a crash leaves the cross-plugin outcome unknown.</p>
     */
    CompletableFuture<DrawerTransferResult> deposit(
            DrawerLocation location,
            ItemStack offered,
            long requestedAmount,
            DrawerOperationContext context
    );

    /**
     * Removes up to {@code requestedAmount} logical items and returns exact, stack-sized copies.
     *
     * <p>The drawer-side removal is journaled and committed before the result completes. A caller
     * must first create its own durable operation or escrow record. If it cannot consume the
     * returned items, it must compensate with a deposit; arbitrary plugins cannot participate in
     * one atomic commit with another plugin's data store.</p>
     */
    CompletableFuture<DrawerTransferResult> withdraw(
            DrawerLocation location,
            long requestedAmount,
            DrawerOperationContext context
    );

    /** Triggers recovery/reconciliation before returning the resulting logical state. */
    CompletableFuture<DrawerQueryResult> reconcile(DrawerLocation location);

    /** Returns the current configured public metadata for every drawer tier. */
    CompletableFuture<DrawerTierQueryResult> tiers();

    /** Creates one tagged drawer item for a configured tier, without command permissions. */
    CompletableFuture<DrawerTierItemResult> createTierItem(String tierId, int amount);
}
