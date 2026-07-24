package com.Teenkung.devStorageDrawer.block;

import com.Teenkung.devStorageDrawer.api.DevStorageDrawerApi;
import com.Teenkung.devStorageDrawer.api.DrawerChangeCause;
import com.Teenkung.devStorageDrawer.api.DrawerLocation;
import com.Teenkung.devStorageDrawer.api.DrawerOperationContext;
import com.Teenkung.devStorageDrawer.api.DrawerQueryResult;
import com.Teenkung.devStorageDrawer.api.DrawerQueryStatus;
import com.Teenkung.devStorageDrawer.api.DrawerSnapshot;
import com.Teenkung.devStorageDrawer.api.DrawerTierInfo;
import com.Teenkung.devStorageDrawer.api.DrawerTierItemResult;
import com.Teenkung.devStorageDrawer.api.DrawerTierItemStatus;
import com.Teenkung.devStorageDrawer.api.DrawerTierQueryResult;
import com.Teenkung.devStorageDrawer.api.DrawerTierQueryStatus;
import com.Teenkung.devStorageDrawer.api.DrawerTransferResult;
import com.Teenkung.devStorageDrawer.api.DrawerTransferStatus;
import com.Teenkung.devStorageDrawer.api.DrawerTransferType;
import com.Teenkung.devStorageDrawer.api.event.DrawerContentsChangedEvent;
import com.Teenkung.devStorageDrawer.api.event.DrawerPreTransactionEvent;
import com.Teenkung.devStorageDrawer.api.event.DrawerTransactionCompletedEvent;
import com.Teenkung.devStorageDrawer.config.DrawerTierDefinition;
import com.Teenkung.devStorageDrawer.domain.DrawerInvariantViolationException;
import com.Teenkung.devStorageDrawer.domain.DrawerStorageTransaction;
import com.Teenkung.devStorageDrawer.domain.DrawerTransactionStatus;
import com.Teenkung.devStorageDrawer.hopper.DrawerHopperBridge;
import com.Teenkung.devStorageDrawer.persistence.DrawerStateReadResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.inventory.ItemStack;

/** Internal service implementation; public consumers only depend on the API package. */
final class DrawerApiService implements DevStorageDrawerApi {

    private final DrawerRuntimeContext context;
    private final DrawerHopperBridge hoppers;
    private final DrawerSellWandBridge externalWithdrawals;
    private final ConcurrentMap<CompletableFuture<?>, PendingFuture> pendingFutures = new ConcurrentHashMap<>();
    private volatile boolean available = true;

    DrawerApiService(
            final DrawerRuntimeContext context,
            final DrawerHopperBridge hoppers,
            final DrawerSellWandBridge externalWithdrawals
    ) {
        this.context = context;
        this.hoppers = hoppers;
        this.externalWithdrawals = externalWithdrawals;
    }

    @Override
    public CompletableFuture<DrawerQueryResult> query(final DrawerLocation location) {
        return dispatchQuery(location, ignored -> queryAt(ignored, false));
    }

    @Override
    public CompletableFuture<DrawerQueryResult> reconcile(final DrawerLocation location) {
        return dispatchQuery(location, checked -> {
            final Barrel barrel = this.context.blocks().barrel(checked.getBlock()).orElse(null);
            if (barrel == null || !this.context.repository().isDrawer(barrel)) {
                return DrawerQueryResult.of(DrawerQueryStatus.NOT_DRAWER);
            }
            this.hoppers.recoverAndRebalance(barrel);
            return queryAt(checked, false);
        });
    }

    @Override
    public CompletableFuture<DrawerTransferResult> deposit(
            final DrawerLocation location,
            final ItemStack offered,
            final long requestedAmount,
            final DrawerOperationContext operationContext
    ) {
        if (!validOffer(offered, requestedAmount) || operationContext == null) {
            return CompletableFuture.completedFuture(transfer(DrawerTransferStatus.INVALID_REQUEST, requestedAmount));
        }
        final ItemStack offeredCopy = offered.clone();
        return dispatchTransfer(
                location, requestedAmount, checked -> depositAt(checked, offeredCopy, requestedAmount, operationContext)
        );
    }

    @Override
    public CompletableFuture<DrawerTransferResult> withdraw(
            final DrawerLocation location,
            final long requestedAmount,
            final DrawerOperationContext operationContext
    ) {
        if (requestedAmount <= 0L || operationContext == null) {
            return CompletableFuture.completedFuture(transfer(DrawerTransferStatus.INVALID_REQUEST, requestedAmount));
        }
        return dispatchTransfer(location, requestedAmount, checked -> withdrawAt(checked, requestedAmount, operationContext));
    }

    @Override
    public CompletableFuture<DrawerTierQueryResult> tiers() {
        final CompletableFuture<DrawerTierQueryResult> future = tracked(
                () -> DrawerTierQueryResult.of(DrawerTierQueryStatus.PLUGIN_DISABLED)
        );
        if (!isAvailable()) {
            future.complete(DrawerTierQueryResult.of(DrawerTierQueryStatus.PLUGIN_DISABLED));
            return future;
        }
        try {
            this.context.execution().runGlobal(ignored -> {
                try {
                    if (!begin(future)) {
                        return;
                    }
                    if (!isAvailable()) {
                        future.complete(DrawerTierQueryResult.of(DrawerTierQueryStatus.PLUGIN_DISABLED));
                        return;
                    }
                    future.complete(DrawerTierQueryResult.found(this.context.settings().tiers().values().stream()
                            .map(DrawerApiService::tierInfo)
                            .sorted((left, right) -> left.id().compareTo(right.id()))
                            .toList()));
                } catch (final RuntimeException exception) {
                    future.complete(DrawerTierQueryResult.of(DrawerTierQueryStatus.UNAVAILABLE));
                }
            });
        } catch (final RuntimeException exception) {
            future.complete(DrawerTierQueryResult.of(DrawerTierQueryStatus.UNAVAILABLE));
        }
        return future;
    }

    @Override
    public CompletableFuture<DrawerTierItemResult> createTierItem(final String tierId, final int amount) {
        if (tierId == null || tierId.isBlank() || amount <= 0 || amount > 64) {
            return CompletableFuture.completedFuture(DrawerTierItemResult.of(DrawerTierItemStatus.INVALID_AMOUNT));
        }
        final CompletableFuture<DrawerTierItemResult> future = tracked(
                () -> DrawerTierItemResult.of(DrawerTierItemStatus.PLUGIN_DISABLED)
        );
        if (!isAvailable()) {
            future.complete(DrawerTierItemResult.of(DrawerTierItemStatus.PLUGIN_DISABLED));
            return future;
        }
        try {
            this.context.execution().runGlobal(ignored -> {
                try {
                if (!begin(future)) {
                    return;
                }
                if (!isAvailable()) {
                    future.complete(DrawerTierItemResult.of(DrawerTierItemStatus.PLUGIN_DISABLED));
                    return;
                }
                final DrawerTierDefinition tier = this.context.tier(tierId).orElse(null);
                if (tier == null) {
                    future.complete(DrawerTierItemResult.of(DrawerTierItemStatus.UNKNOWN_TIER));
                    return;
                }
                final ItemStack item = tier.createItem();
                item.setAmount(amount);
                future.complete(DrawerTierItemResult.created(item));
                } catch (final RuntimeException exception) {
                    future.complete(DrawerTierItemResult.of(DrawerTierItemStatus.PLUGIN_DISABLED));
                }
            });
        } catch (final RuntimeException exception) {
            future.complete(DrawerTierItemResult.of(DrawerTierItemStatus.PLUGIN_DISABLED));
        }
        return future;
    }

    void onCommitted(final DrawerRuntimeContext.DrawerMutation mutation) {
        Bukkit.getPluginManager().callEvent(new DrawerContentsChangedEvent(
                snapshot(mutation.barrel(), mutation.state(), mutation.physicalProxyCount()),
                mutation.cause()
        ));
    }

    void disable() {
        this.available = false;
        this.pendingFutures.values().forEach(PendingFuture::cancelIfPending);
    }

    private DrawerTransferResult depositAt(
            final Location location,
            final ItemStack offered,
            final long requestedAmount,
            final DrawerOperationContext operationContext
    ) {
        final ReadyDrawer ready = readyAt(location);
        if (ready == null) {
            return transfer(statusFor(location), requestedAmount);
        }
        final DrawerPreTransactionEvent event = new DrawerPreTransactionEvent(
                DrawerTransferType.DEPOSIT, ready.snapshot(), offered, requestedAmount, operationContext
        );
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return transfer(DrawerTransferStatus.CANCELLED, requestedAmount, ready.snapshot());
        }
        try {
            final DrawerTierDefinition tier = this.context.tier(ready.state().tierId()).orElse(null);
            if (tier == null) {
                return transfer(DrawerTransferStatus.CORRUPT, requestedAmount, ready.snapshot());
            }
            final DrawerStorageTransaction transaction = this.context.storage().deposit(
                    ready.state().hasTemplate()
                            ? ready.state()
                            : ready.state().initializeCapacityForFirstItem(tier.tier(), offered),
                    ready.stock().count(),
                    offered,
                    requestedAmount
            );
            if (!transaction.accepted()) {
                return transfer(map(transaction.status()), requestedAmount, ready.snapshot());
            }
            if (!this.context.repository().save(ready.barrel(), transaction.stateAfter())) {
                return transfer(DrawerTransferStatus.PERSISTENCE_FAILURE, requestedAmount, ready.snapshot());
            }
            try {
                this.context.notifyCommitted(
                        ready.barrel(), transaction.stateAfter(), transaction.physicalAfter(), DrawerChangeCause.API_DEPOSIT
                );
                this.hoppers.queueRebalance(ready.barrel());
            } catch (final RuntimeException exception) {
                this.context.logger().warning("Drawer API deposit committed at " + location
                        + " but post-commit refresh failed: " + exception.getMessage());
            }
            final DrawerTransferResult result = applied(
                    requestedAmount,
                    transaction.acceptedAmount(),
                    snapshot(ready.barrel(), transaction.stateAfter(), transaction.physicalAfter()),
                    List.of()
            );
            Bukkit.getPluginManager().callEvent(new DrawerTransactionCompletedEvent(
                    DrawerTransferType.DEPOSIT, result, operationContext
            ));
            return result;
        } catch (final DrawerInvariantViolationException | IllegalArgumentException exception) {
            this.context.logger().warning("Rejected external drawer deposit at " + location + ": " + exception.getMessage());
            return transfer(DrawerTransferStatus.INVALID_REQUEST, requestedAmount, ready.snapshot());
        }
    }

    private DrawerTransferResult withdrawAt(
            final Location location,
            final long requestedAmount,
            final DrawerOperationContext operationContext
    ) {
        final ReadyDrawer ready = readyAt(location);
        if (ready == null) {
            return transfer(statusFor(location), requestedAmount);
        }
        if (ready.snapshot().isEmpty()) {
            return transfer(DrawerTransferStatus.EMPTY, requestedAmount, ready.snapshot());
        }
        final DrawerPreTransactionEvent event = new DrawerPreTransactionEvent(
                DrawerTransferType.WITHDRAW, ready.snapshot(), null, requestedAmount, operationContext
        );
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return transfer(DrawerTransferStatus.CANCELLED, requestedAmount, ready.snapshot());
        }
        final DrawerSellWandBridge.Withdrawal withdrawal = this.externalWithdrawals.withdraw(
                ready.barrel(), requestedAmount, DrawerChangeCause.API_WITHDRAW
        );
        if (!withdrawal.success()) {
            return transfer(DrawerTransferStatus.PERSISTENCE_FAILURE, requestedAmount, ready.snapshot());
        }
        final DrawerQueryResult after = queryAt(location, false);
        final Optional<DrawerSnapshot> snapshot = after.snapshot();
        final DrawerTransferResult result = applied(
                requestedAmount,
                withdrawal.amount(),
                snapshot.orElse(ready.snapshot()),
                splitStacks(withdrawal.template(), withdrawal.amount())
        );
        Bukkit.getPluginManager().callEvent(new DrawerTransactionCompletedEvent(
                DrawerTransferType.WITHDRAW, result, operationContext
        ));
        return result;
    }

    private DrawerQueryResult queryAt(final Location location, final boolean repair) {
        final Barrel barrel = this.context.blocks().barrel(location.getBlock()).orElse(null);
        if (barrel == null || !this.context.repository().isDrawer(barrel)) {
            return DrawerQueryResult.of(DrawerQueryStatus.NOT_DRAWER);
        }
        final DrawerStateReadResult state = this.context.repository().read(barrel);
        if (state instanceof DrawerStateReadResult.Corrupt) {
            return DrawerQueryResult.of(DrawerQueryStatus.CORRUPT);
        }
        if (!(state instanceof DrawerStateReadResult.Valid valid)) {
            return DrawerQueryResult.of(DrawerQueryStatus.CORRUPT);
        }
        final DrawerBlockAccess.PhysicalStock stock = this.context.blocks().inspect(barrel, valid.state());
        if (!stock.matchesTemplate() || valid.state().hasPendingProxyJournal()) {
            if (repair) {
                this.hoppers.recoverAndRebalance(barrel);
                return queryAt(location, false);
            }
            return DrawerQueryResult.of(DrawerQueryStatus.RECOVERING);
        }
        return DrawerQueryResult.found(snapshot(barrel, valid.state(), stock.count()));
    }

    private ReadyDrawer readyAt(final Location location) {
        final DrawerQueryResult result = queryAt(location, false);
        if (result.status() != DrawerQueryStatus.FOUND) {
            return null;
        }
        final Barrel barrel = this.context.blocks().barrel(location.getBlock()).orElse(null);
        if (barrel == null) {
            return null;
        }
        final DrawerStateReadResult state = this.context.repository().read(barrel);
        if (!(state instanceof DrawerStateReadResult.Valid valid)) {
            return null;
        }
        final DrawerBlockAccess.PhysicalStock stock = this.context.blocks().inspect(barrel, valid.state());
        return new ReadyDrawer(barrel, valid.state(), stock, result.snapshot().orElseThrow());
    }

    private DrawerTransferStatus statusFor(final Location location) {
        return switch (queryAt(location, false).status()) {
            case NOT_DRAWER -> DrawerTransferStatus.NOT_DRAWER;
            case WORLD_UNAVAILABLE -> DrawerTransferStatus.WORLD_UNAVAILABLE;
            case UNAVAILABLE -> DrawerTransferStatus.UNAVAILABLE;
            case RECOVERING -> DrawerTransferStatus.RECOVERING;
            case CORRUPT -> DrawerTransferStatus.CORRUPT;
            case PLUGIN_DISABLED -> DrawerTransferStatus.PLUGIN_DISABLED;
            case FOUND -> DrawerTransferStatus.PERSISTENCE_FAILURE;
        };
    }

    private CompletableFuture<DrawerQueryResult> dispatchQuery(
            final DrawerLocation reference,
            final Function<Location, DrawerQueryResult> action
    ) {
        if (reference == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("location must not be null"));
        }
        final CompletableFuture<DrawerQueryResult> future = tracked(
                () -> DrawerQueryResult.of(DrawerQueryStatus.PLUGIN_DISABLED)
        );
        if (!isAvailable()) {
            future.complete(DrawerQueryResult.of(DrawerQueryStatus.PLUGIN_DISABLED));
            return future;
        }
        try {
            this.context.execution().runGlobal(ignored -> {
                try {
                if (!isAvailable()) {
                    future.complete(DrawerQueryResult.of(DrawerQueryStatus.PLUGIN_DISABLED));
                    return;
                }
                final World world = Bukkit.getWorld(reference.worldId());
                if (world == null) {
                    future.complete(DrawerQueryResult.of(DrawerQueryStatus.WORLD_UNAVAILABLE));
                    return;
                }
                final Location location = new Location(world, reference.blockX(), reference.blockY(), reference.blockZ());
                try {
                    this.context.execution().executeAt(location, () -> {
                        if (!begin(future)) {
                            return;
                        }
                        try {
                            future.complete(action.apply(location));
                        } catch (final RuntimeException exception) {
                            this.context.logger().warning("Drawer API lookup failed at " + location + ": " + exception.getMessage());
                            future.complete(DrawerQueryResult.of(DrawerQueryStatus.CORRUPT));
                        }
                    });
                } catch (final RuntimeException exception) {
                    future.complete(DrawerQueryResult.of(DrawerQueryStatus.UNAVAILABLE));
                }
                } catch (final RuntimeException exception) {
                    future.complete(DrawerQueryResult.of(DrawerQueryStatus.UNAVAILABLE));
                }
            });
        } catch (final RuntimeException exception) {
            future.complete(DrawerQueryResult.of(DrawerQueryStatus.PLUGIN_DISABLED));
        }
        return future;
    }

    private CompletableFuture<DrawerTransferResult> dispatchTransfer(
            final DrawerLocation reference,
            final long requestedAmount,
            final Function<Location, DrawerTransferResult> action
    ) {
        final CompletableFuture<DrawerTransferResult> future = tracked(
                () -> transfer(DrawerTransferStatus.PLUGIN_DISABLED, requestedAmount)
        );
        if (reference == null) {
            future.complete(transfer(DrawerTransferStatus.INVALID_REQUEST, requestedAmount));
            return future;
        }
        if (!isAvailable()) {
            future.complete(transfer(DrawerTransferStatus.PLUGIN_DISABLED, requestedAmount));
            return future;
        }
        try {
            this.context.execution().runGlobal(ignored -> {
                try {
                if (!isAvailable()) {
                    future.complete(transfer(DrawerTransferStatus.PLUGIN_DISABLED, requestedAmount));
                    return;
                }
                final World world = Bukkit.getWorld(reference.worldId());
                if (world == null) {
                    future.complete(transfer(DrawerTransferStatus.WORLD_UNAVAILABLE, requestedAmount));
                    return;
                }
                final Location location = new Location(world, reference.blockX(), reference.blockY(), reference.blockZ());
                try {
                    this.context.execution().executeAt(location, () -> {
                        if (!begin(future)) {
                            return;
                        }
                        try {
                            future.complete(action.apply(location));
                        } catch (final RuntimeException exception) {
                            this.context.logger().warning("Drawer API transfer failed at " + location + ": " + exception.getMessage());
                            future.complete(transfer(DrawerTransferStatus.PERSISTENCE_FAILURE, requestedAmount));
                        }
                    });
                } catch (final RuntimeException exception) {
                    future.complete(transfer(DrawerTransferStatus.UNAVAILABLE, requestedAmount));
                }
                } catch (final RuntimeException exception) {
                    future.complete(transfer(DrawerTransferStatus.PLUGIN_DISABLED, requestedAmount));
                }
            });
        } catch (final RuntimeException exception) {
            future.complete(transfer(DrawerTransferStatus.PLUGIN_DISABLED, requestedAmount));
        }
        return future;
    }

    private static DrawerTierInfo tierInfo(final DrawerTierDefinition definition) {
        return new DrawerTierInfo(
                definition.tier().id(), definition.tier().stackCapacity(), definition.tier().optionalPlacementPermission()
        );
    }

    private boolean isAvailable() {
        return this.available && this.context.plugin().isEnabled();
    }

    private <T> CompletableFuture<T> tracked(final Supplier<T> disabledResult) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        this.pendingFutures.put(future, new PendingFuture(() -> future.complete(disabledResult.get())));
        future.whenComplete((ignored, exception) -> this.pendingFutures.remove(future));
        return future;
    }

    private boolean begin(final CompletableFuture<?> future) {
        final PendingFuture pending = this.pendingFutures.get(future);
        return pending != null && pending.begin();
    }

    private static final class PendingFuture {
        private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);
        private final Runnable cancellation;

        private PendingFuture(final Runnable cancellation) {
            this.cancellation = cancellation;
        }

        private boolean begin() {
            return this.state.compareAndSet(State.PENDING, State.RUNNING);
        }

        private void cancelIfPending() {
            if (this.state.compareAndSet(State.PENDING, State.CANCELLED)) {
                this.cancellation.run();
            }
        }

        private enum State {
            PENDING,
            RUNNING,
            CANCELLED
        }
    }

    private static boolean validOffer(final ItemStack offered, final long amount) {
        return offered != null && !offered.getType().isAir() && offered.getAmount() > 0
                && amount > 0L && amount <= offered.getAmount();
    }

    private static DrawerTransferStatus map(final DrawerTransactionStatus status) {
        return switch (status) {
            case APPLIED -> DrawerTransferStatus.APPLIED;
            case EMPTY -> DrawerTransferStatus.EMPTY;
            case FULL -> DrawerTransferStatus.FULL;
            case ITEM_MISMATCH -> DrawerTransferStatus.ITEM_MISMATCH;
            case INVALID_REQUEST -> DrawerTransferStatus.INVALID_REQUEST;
        };
    }

    private static DrawerTransferResult transfer(final DrawerTransferStatus status, final long requestedAmount) {
        return transfer(status, requestedAmount, Optional.empty());
    }

    private static DrawerTransferResult transfer(
            final DrawerTransferStatus status,
            final long requestedAmount,
            final DrawerSnapshot snapshot
    ) {
        return transfer(status, requestedAmount, Optional.of(snapshot));
    }

    private static DrawerTransferResult transfer(
            final DrawerTransferStatus status,
            final long requestedAmount,
            final Optional<DrawerSnapshot> snapshot
    ) {
        return new DrawerTransferResult(status, Math.max(0L, requestedAmount), 0L, snapshot, List.of());
    }

    private static DrawerTransferResult applied(
            final long requestedAmount,
            final long acceptedAmount,
            final DrawerSnapshot snapshot,
            final List<ItemStack> withdrawnItems
    ) {
        return new DrawerTransferResult(
                DrawerTransferStatus.APPLIED, requestedAmount, acceptedAmount, Optional.of(snapshot), withdrawnItems
        );
    }

    private static DrawerSnapshot snapshot(
            final Barrel barrel,
            final com.Teenkung.devStorageDrawer.domain.DrawerState state,
            final long physicalProxyAmount
    ) {
        return new DrawerSnapshot(
                DrawerLocation.from(barrel.getLocation()),
                state.tierId(),
                state.template().orElse(null),
                state.totalForPhysical(physicalProxyAmount),
                state.capacitySnapshot(),
                physicalProxyAmount
        );
    }

    private static List<ItemStack> splitStacks(final ItemStack template, long amount) {
        final List<ItemStack> stacks = new ArrayList<>();
        final int maximum = template.getMaxStackSize();
        while (amount > 0L) {
            final int stackAmount = (int) Math.min(amount, (long) maximum);
            final ItemStack stack = template.clone();
            stack.setAmount(stackAmount);
            stacks.add(stack);
            amount -= stackAmount;
        }
        return List.copyOf(stacks);
    }

    private record ReadyDrawer(
            Barrel barrel,
            com.Teenkung.devStorageDrawer.domain.DrawerState state,
            DrawerBlockAccess.PhysicalStock stock,
            DrawerSnapshot snapshot
    ) {
    }
}
