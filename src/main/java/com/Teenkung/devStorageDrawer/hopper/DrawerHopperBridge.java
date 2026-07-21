package com.teenkung.devstoragedrawer.hopper;

import com.teenkung.devstoragedrawer.api.DrawerChangeCause;
import com.teenkung.devstoragedrawer.block.DrawerBlockAccess;
import com.teenkung.devstoragedrawer.block.DrawerRuntimeContext;
import com.teenkung.devstoragedrawer.config.DrawerTierDefinition;
import com.teenkung.devstoragedrawer.domain.DrawerInvariantViolationException;
import com.teenkung.devstoragedrawer.domain.DrawerItemIdentity;
import com.teenkung.devstoragedrawer.domain.DrawerJournalKind;
import com.teenkung.devstoragedrawer.domain.DrawerJournalReconciliation;
import com.teenkung.devstoragedrawer.domain.DrawerRebalancePlan;
import com.teenkung.devstoragedrawer.domain.DrawerState;
import com.teenkung.devstoragedrawer.domain.DrawerStorageTransaction;
import com.teenkung.devstoragedrawer.persistence.DrawerStateReadResult;
import com.teenkung.devstoragedrawer.receipt.DrawerWithdrawalCoordinator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Chunk;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.Hopper;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.HopperInventorySearchEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Lets vanilla hoppers move the real barrel mirror while the persisted drawer total remains
 * authoritative. Paper's inventory-search hook is the preventative input guard; move events and
 * the watcher reconcile the actual physical result.
 */
public final class DrawerHopperBridge implements Listener {

    /** Lets vanilla finish applying an InventoryMoveItemEvent before the physical mirror is read. */
    private static final long INVENTORY_MOVE_COMMIT_DELAY_TICKS = 1L;

    private final DrawerRuntimeContext context;
    private final DrawerWithdrawalCoordinator withdrawals;
    private final ConcurrentMap<DrawerKey, RebalanceWork> queuedRebalances = new ConcurrentHashMap<>();
    private final Set<DrawerKey> quarantinedMismatches = ConcurrentHashMap.newKeySet();
    private final Set<DrawerKey> quarantinedInvalidStates = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<DrawerKey, WatchedDrawer> watchedDrawers = new ConcurrentHashMap<>();
    private final AtomicBoolean active = new AtomicBoolean(true);

    public DrawerHopperBridge(
            final DrawerRuntimeContext context,
            final DrawerWithdrawalCoordinator withdrawals
    ) {
        this.context = Objects.requireNonNull(context, "context");
        this.withdrawals = Objects.requireNonNull(withdrawals, "withdrawals");
    }

    /**
     * Paper invokes this before a hopper uses the block it faces as a destination. Redirecting a
     * rejected search to {@code null} means the hopper keeps its item; this still works on hopper
     * implementations which bypass {@link InventoryMoveItemEvent}.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHopperDestinationSearch(final HopperInventorySearchEvent event) {
        if (!isActive() || !this.context.settings().automation().enabled()
                || event.getContainerType() != HopperInventorySearchEvent.ContainerType.DESTINATION) {
            return;
        }
        final Barrel drawer = this.context.blocks().barrel(event.getSearchBlock()).orElse(null);
        if (drawer == null || !this.context.repository().isDrawer(drawer)) {
            return;
        }
        if (!context.execution().isOwnedByCurrentRegion(drawer.getLocation())
                || !context.execution().isOwnedByCurrentRegion(event.getBlock().getLocation())) {
            event.setInventory(null);
            debug("rejected hopper destination search outside the current region for " + drawer.getLocation());
            return;
        }
        if (!(event.getBlock().getState() instanceof Hopper hopper)
                || !permitsHopperInventory(hopper, drawer)) {
            event.setInventory(null);
            debug("rejected hopper destination search for " + drawer.getLocation());
            return;
        }
        // InventoryMoveItemEvent may be disabled. Reconcile after the search so a successful
        // eventless transfer is observed promptly instead of waiting for the fallback poll.
        queueRebalance(drawer);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryMove(final InventoryMoveItemEvent event) {
        if (!isActive() || !this.context.settings().automation().enabled()) {
            debug("ignored inventory move because automation is disabled");
            return;
        }
        if (event.isCancelled()) {
            debug("ignored inventory move because another listener cancelled it before the drawer listener ran");
            return;
        }

        final Barrel source = barrel(event.getSource());
        final Barrel destination = barrel(event.getDestination());
        final boolean sourceDrawer = source != null && this.context.repository().isDrawer(source);
        final boolean destinationDrawer = destination != null && this.context.repository().isDrawer(destination);
        debug("move " + event.getItem().getType() + " x" + event.getItem().getAmount()
                + " source=" + inventoryDescription(event.getSource(), source, sourceDrawer)
                + " destination=" + inventoryDescription(event.getDestination(), destination, destinationDrawer));
        if ((source != null && !context.execution().isOwnedByCurrentRegion(source.getLocation()))
                || (destination != null && !context.execution().isOwnedByCurrentRegion(destination.getLocation()))) {
            // Never inspect or mutate a foreign Folia region from the hopper event. Adjacent
            // transfers are normally region-co-owned; unusual cross-region attempts fail closed.
            event.setCancelled(true);
            debug("cancelled move because a drawer is outside the current Folia region");
            return;
        }
        boolean cancel = false;
        if (sourceDrawer) {
            cancel = !permitExtract(source, event.getItem());
        }
        if (!cancel && destinationDrawer) {
            cancel = !permitInsert(destination, event.getItem());
        }
        if (cancel) {
            event.setCancelled(true);
            debug("cancelled move because the drawer guard rejected it");
            return;
        }

        if (sourceDrawer) {
            queueRebalance(source);
        }
        if (destinationDrawer) {
            queueRebalance(destination);
        }
        if (sourceDrawer || destinationDrawer) {
            debug("accepted move and queued drawer reconciliation");
        }
    }

    /**
     * Schedules region-owned reconciliation while coalescing hopper bursts. A move observed while
     * work is already queued marks the drawer dirty, guaranteeing a trailing pass instead of
     * silently losing the later inventory change.
     */
    public void queueRebalance(final Barrel barrel) {
        if (!isActive()) {
            return;
        }
        final DrawerKey key = DrawerKey.of(barrel.getLocation());
        while (true) {
            final RebalanceWork existing = this.queuedRebalances.get(key);
            if (existing != null) {
                if (existing.requestTrailingPass()) {
                    debug("marked " + barrel.getLocation() + " dirty because reconciliation is already queued");
                    return;
                }
                this.queuedRebalances.remove(key, existing);
                continue;
            }

            final RebalanceWork created = new RebalanceWork();
            if (this.queuedRebalances.putIfAbsent(key, created) == null) {
                final Location location = barrel.getLocation();
                debug("queued reconciliation for " + location);
                scheduleRebalancePass(key, location, created);
                return;
            }
        }
    }

    /** Starts a bounded fallback watcher for a loaded drawer whose hopper implementation bypasses move events. */
    public void watch(final Barrel barrel) {
        if (!isActive()) {
            return;
        }
        final Location location = barrel.getLocation();
        final DrawerKey key = DrawerKey.of(location);
        final WatchedDrawer watcher = new WatchedDrawer(-1L);
        if (this.watchedDrawers.putIfAbsent(key, watcher) == null) {
            debug("started fallback hopper watcher for " + location);
            scheduleFallbackPoll(key, location, watcher);
        }
    }

    /** Stops all fallback work for an unloading chunk; chunk load repair re-registers tagged drawers. */
    public void unwatch(final Chunk chunk) {
        final UUID worldId = chunk.getWorld().getUID();
        final int chunkX = chunk.getX();
        final int chunkZ = chunk.getZ();
        this.watchedDrawers.keySet().removeIf(key -> key.worldId().equals(worldId)
                && (key.x() >> 4) == chunkX && (key.z() >> 4) == chunkZ);
        this.queuedRebalances.keySet().removeIf(key -> key.worldId().equals(worldId)
                && (key.x() >> 4) == chunkX && (key.z() >> 4) == chunkZ);
        this.quarantinedMismatches.removeIf(key -> key.worldId().equals(worldId)
                && (key.x() >> 4) == chunkX && (key.z() >> 4) == chunkZ);
        this.quarantinedInvalidStates.removeIf(key -> key.worldId().equals(worldId)
                && (key.x() >> 4) == chunkX && (key.z() >> 4) == chunkZ);
    }

    /** Stops all queued and fallback work for a drawer that is being broken. */
    public void unwatch(final Barrel barrel) {
        final DrawerKey key = DrawerKey.of(barrel.getLocation());
        this.watchedDrawers.remove(key);
        this.queuedRebalances.remove(key);
        this.quarantinedMismatches.remove(key);
        this.quarantinedInvalidStates.remove(key);
    }

    /**
     * Makes all delayed callbacks from this plugin instance inert. This is essential for reload
     * tools: Folia location tasks can already be queued when Bukkit unregisters listeners.
     */
    public void shutdown() {
        if (!this.active.compareAndSet(true, false)) {
            return;
        }
        this.watchedDrawers.clear();
        this.queuedRebalances.clear();
        this.quarantinedMismatches.clear();
        this.quarantinedInvalidStates.clear();
    }

    private void scheduleRebalancePass(
            final DrawerKey key,
            final Location location,
            final RebalanceWork work
    ) {
        try {
            this.context.execution().runLaterAt(
                    location,
                    Math.addExact(
                            this.context.settings().automation().rebalanceDelayTicks(),
                            INVENTORY_MOVE_COMMIT_DELAY_TICKS
                    ),
                    task -> {
                        if (!isActive() || this.queuedRebalances.get(key) != work) {
                            return;
                        }
                        try {
                            final Block block = location.getBlock();
                            this.context.blocks().barrel(block).ifPresent(this::recoverAndRebalance);
                        } finally {
                            finishRebalancePass(key, location, work);
                        }
                    }
            );
        } catch (final RuntimeException exception) {
            this.queuedRebalances.remove(key, work);
            throw exception;
        }
    }

    private void scheduleFallbackPoll(
            final DrawerKey key,
            final Location location,
            final WatchedDrawer watcher
    ) {
        this.context.execution().runLaterAt(
                location,
                this.context.settings().automation().fallbackPollIntervalTicks(),
                ignored -> {
                    if (!isActive() || this.watchedDrawers.get(key) != watcher) {
                        return;
                    }
                    try {
                        pollFallbackWatcher(key, location, watcher);
                    } finally {
                        if (isActive() && this.watchedDrawers.get(key) == watcher) {
                            scheduleFallbackPoll(key, location, watcher);
                        }
                    }
                }
        );
    }

    private void pollFallbackWatcher(
            final DrawerKey key,
            final Location location,
            final WatchedDrawer watcher
    ) {
        if (!isActive() || this.watchedDrawers.get(key) != watcher) {
            return;
        }
        final Barrel barrel = this.context.blocks().barrel(location.getBlock()).orElse(null);
        if (barrel == null || !this.context.repository().isDrawer(barrel)) {
            this.watchedDrawers.remove(key, watcher);
            return;
        }
        final DrawerStateReadResult read = this.context.repository().read(barrel);
        if (!(read instanceof DrawerStateReadResult.Valid valid)) {
            return;
        }
        final DrawerBlockAccess.PhysicalStock stock = this.context.blocks().inspect(barrel, valid.state());
        if (this.watchedDrawers.get(key) != watcher) {
            return;
        }
        final long previousCount = watcher.physicalCount();
        final boolean unobservedNonEmptyDrawer = previousCount < 0L && stock.count() > 0L;
        final boolean physicalCountChanged = previousCount >= 0L && previousCount != stock.count();
        final boolean mirrorExpectationChanged = valid.state().usesReservedMirror()
                && valid.state().expectedMirrorCount() != stock.count();
        final boolean physicalTemplateChanged = !stock.matchesTemplate();
        if (unobservedNonEmptyDrawer || physicalCountChanged || mirrorExpectationChanged || physicalTemplateChanged) {
            debug("fallback watcher detected physical mirror change at " + location + ": count "
                    + previousCount + " -> " + stock.count() + ", templateMatches=" + stock.matchesTemplate());
            queueRebalance(barrel);
        }
        watcher.observe(stock.count());
    }

    private void finishRebalancePass(
            final DrawerKey key,
            final Location location,
            final RebalanceWork work
    ) {
        if (!isActive() || this.queuedRebalances.get(key) != work) {
            return;
        }
        if (work.finishPass()) {
            scheduleRebalancePass(key, location, work);
            return;
        }
        this.queuedRebalances.remove(key, work);
    }

    /** Invoked from chunk repair as well as delayed hopper work, always on the barrel's region scheduler. */
    public void recoverAndRebalance(final Barrel barrel) {
        if (!isActive()) {
            return;
        }
        // A placed Barrel BlockState snapshots both PDC and inventory contents. Inventory move
        // events can hand us a state captured before the vanilla transfer completed; saving PDC
        // through that object would restore its stale inventory and erase the transferred item.
        // Always begin reconciliation from a newly captured placed state.
        final Barrel current = this.context.blocks().barrel(barrel.getBlock()).orElse(null);
        if (current == null || !this.context.repository().isDrawer(current)) {
            return;
        }
        watch(current);
        final DrawerStateReadResult result = this.context.repository().read(current);
        if (!(result instanceof DrawerStateReadResult.Valid valid)) {
            if (result instanceof DrawerStateReadResult.Corrupt corrupt) {
                this.context.logger().warning("Quarantined corrupt drawer at " + current.getLocation() + ": " + corrupt.reason());
            }
            debug("skipped reconciliation for " + current.getLocation() + " because its state is not valid");
            return;
        }

        DrawerState state = valid.state();
        DrawerBlockAccess.PhysicalStock stock = this.context.blocks().inspect(current, state);
        observePhysical(current, stock.count());
        debug("reconciling " + current.getLocation() + " template=" + state.hasTemplate()
                + " stored=" + state.storedTotal() + " expectedMirror=" + state.expectedMirrorCount()
                + " physical=" + stock.count() + " matches=" + stock.matchesTemplate()
                + " pendingJournal=" + state.hasPendingProxyJournal());
        if (!stock.matchesTemplate()) {
            final Optional<DrawerState> adopted = adoptUniformPhysicalTemplate(current, state, stock.count());
            if (adopted.isEmpty()) {
                final DrawerKey key = DrawerKey.of(current.getLocation());
                if (this.quarantinedMismatches.add(key)) {
                    this.context.logger().warning("Quarantined drawer with mismatched proxy inventory at " + current.getLocation());
                }
                debug("quarantined reconciliation because physical stock does not match the drawer template");
                return;
            }
            state = adopted.get();
            stock = this.context.blocks().inspect(current, state);
        }
        this.quarantinedMismatches.remove(DrawerKey.of(current.getLocation()));

        if (!state.usesReservedMirror()) {
            try {
                state = state.migrateToReservedMirror(stock.count());
            } catch (final DrawerInvariantViolationException | IllegalArgumentException exception) {
                this.context.logger().warning("Could not migrate legacy drawer at " + current.getLocation()
                        + " to the reserved mirror: " + exception.getMessage());
                return;
            }
            if (!this.context.repository().save(current, state)) {
                return;
            }
            debug("migrated legacy drawer at " + current.getLocation() + " to storedTotal="
                    + state.storedTotal() + " expectedMirror=" + state.expectedMirrorCount());
        }

        try {
            if (state.proxyJournal()
                    .filter(journal -> journal.kind() == DrawerJournalKind.PLAYER_WITHDRAWAL)
                    .isPresent()) {
                this.withdrawals.recover(current, state, stock.count(), () -> this.queueRebalance(current));
                debug("delegated reconciliation of a player withdrawal journal");
                return;
            }
            final DrawerJournalReconciliation recovery = this.context.storage().reconcile(state, stock.count());
            if (recovery.requiresManualRecovery()) {
                this.context.logger().warning("Quarantined drawer with impossible proxy journal at " + current.getLocation());
                debug("quarantined reconciliation because the proxy journal is impossible");
                return;
            }
            state = recovery.state();
            this.quarantinedInvalidStates.remove(DrawerKey.of(current.getLocation()));
            if (state != valid.state() && !this.context.repository().save(current, state)) {
                return;
            }
            if (state.hasTemplate() && state.totalForPhysical(stock.count()) == 0L) {
                final DrawerState cleared = state.withoutTemplate();
                if (this.context.repository().save(current, cleared)) {
                    this.context.notifyCommitted(current, cleared, 0L, DrawerChangeCause.AUTOMATION);
                    debug("cleared empty drawer and queued display refresh");
                }
                return;
            }
            if (!state.hasTemplate()) {
                this.context.notifyCommitted(current, state, stock.count(), DrawerChangeCause.AUTOMATION);
                debug("drawer remains empty and queued display refresh");
                return;
            }

            DrawerRebalancePlan plan = this.context.storage().prepareRebalance(
                    state,
                    stock.count(),
                    this.context.settings().automation().outputProxySlots()
            );
            if (!plan.requiresRebalance() && DrawerProxyInventory.needsRepack(
                    current,
                    state.requireTemplate(),
                    stock.count(),
                    this.context.settings().automation().outputProxySlots()
            )) {
                plan = DrawerRebalancePlan.repack(state, stock.count(), System.currentTimeMillis());
                debug("queued same-count proxy repack to restore hopper output stack sizes");
            }
            if (!plan.requiresRebalance()) {
                this.context.notifyCommitted(current, state, stock.count(), DrawerChangeCause.AUTOMATION);
                debug("physical proxy already matches target; queued display refresh for total "
                        + state.totalForPhysical(stock.count()));
                return;
            }
            if (!this.context.repository().save(current, plan.journaledState())) {
                return;
            }
            if (!DrawerProxyInventory.rewrite(
                    current,
                    state.requireTemplate(),
                    plan.physicalTarget(),
                    this.context.settings().automation().outputProxySlots()
            )) {
                return;
            }
            // A Barrel BlockState also snapshots its inventory. Reacquire it after the live
            // inventory rewrite so persisting PDC cannot restore stale pre-rewrite contents.
            final Barrel rewritten = this.context.blocks().barrel(current.getBlock()).orElse(null);
            if (rewritten != null && this.context.repository().save(rewritten, plan.committedState())) {
                observePhysical(rewritten, plan.physicalTarget());
                this.context.notifyCommitted(
                        rewritten, plan.committedState(), plan.physicalTarget(), DrawerChangeCause.AUTOMATION
                );
                debug("rewrote proxy to " + plan.physicalTarget() + " item(s) and queued display refresh");
            }
        } catch (final DrawerInvariantViolationException | IllegalArgumentException exception) {
            final DrawerKey key = DrawerKey.of(current.getLocation());
            if (this.quarantinedInvalidStates.add(key)) {
                this.context.logger().warning("Quarantined drawer after invalid hopper state at "
                        + current.getLocation() + ": " + exception.getMessage());
            }
            debug("drawer remains quarantined after invalid hopper state at " + current.getLocation()
                    + ": " + exception.getMessage());
        }
    }

    private boolean permitInsert(final Barrel barrel, final ItemStack item) {
        // InventoryMoveItemEvent holders may be snapshots captured before the destination search
        // persisted a first-item template. Always guard and save through a fresh placed state.
        final Barrel current = this.context.blocks().barrel(barrel.getBlock()).orElse(null);
        if (current == null || !this.context.repository().isDrawer(current)) {
            debug("rejected input into " + barrel.getLocation() + " because the live drawer is unavailable");
            return false;
        }
        final DrawerStateReadResult read = this.context.repository().read(current);
        if (!(read instanceof DrawerStateReadResult.Valid valid)) {
            debug("rejected input into " + current.getLocation() + " because the drawer state is invalid");
            return false;
        }
        DrawerState state = valid.state();
        final DrawerBlockAccess.PhysicalStock stock = this.context.blocks().inspect(current, state);
        if (!stock.matchesTemplate() || state.hasPendingProxyJournal()) {
            debug("rejected input into " + current.getLocation() + " because physical stock mismatches="
                    + !stock.matchesTemplate() + " pendingJournal=" + state.hasPendingProxyJournal());
            return false;
        }
        try {
            if (!state.hasTemplate()) {
                final DrawerTierDefinition tier = this.context.tier(state.tierId()).orElse(null);
                if (tier == null) {
                    debug("rejected first input into " + current.getLocation() + " because tier " + state.tierId() + " is missing");
                    return false;
                }
                state = state.initializeCapacityForFirstItem(tier.tier(), item);
            }
            final DrawerStorageTransaction transaction = this.context.storage().acceptProxyInsert(
                    state,
                    stock.count(),
                    item,
                    item.getAmount()
            );
            if (!transaction.accepted() || transaction.acceptedAmount() != item.getAmount()) {
                debug("rejected input into " + current.getLocation() + " because accepted="
                        + transaction.acceptedAmount() + " requested=" + item.getAmount());
                return false;
            }
            // Do not credit the authoritative total before vanilla has actually inserted the
            // stack. The delayed/eventless reconciler observes the real barrel delta instead.
            // A new empty drawer still needs its template persisted before that insertion.
            final boolean initialized = !valid.state().hasTemplate();
            final boolean saved = !initialized || this.context.repository().save(current, state);
            debug("input guard for " + current.getLocation() + " accepted=" + saved
                    + " physicalBefore=" + stock.count());
            return saved;
        } catch (final DrawerInvariantViolationException | IllegalArgumentException exception) {
            debug("rejected input into " + current.getLocation() + " because " + exception.getMessage());
            return false;
        }
    }

    /**
     * Hoppers choose an item after their destination is resolved. Require every occupied hopper
     * slot to match the drawer, so a later slot can never bypass this pre-transfer guard. A mixed
     * hopper pauses until its wrong item is removed; it does not lose or insert that item.
     */
    private boolean permitsHopperInventory(final Hopper hopper, final Barrel drawer) {
        final DrawerStateReadResult read = this.context.repository().read(drawer);
        if (!(read instanceof DrawerStateReadResult.Valid valid)) {
            return false;
        }

        ItemStack first = null;
        for (final ItemStack item : hopper.getInventory().getContents()) {
            if (!DrawerItemIdentity.isStorageCandidate(item)) {
                continue;
            }
            if (first == null) {
                first = item;
                continue;
            }
            final boolean matches = valid.state().hasTemplate()
                    ? valid.state().matchesTemplate(item)
                    : DrawerItemIdentity.matches(first, item);
            if (!matches) {
                debug("blocked mixed hopper inventory from inserting into " + drawer.getLocation());
                return false;
            }
        }
        if (first == null) {
            return true;
        }
        if (valid.state().hasTemplate() && !valid.state().matchesTemplate(first)) {
            debug("blocked wrong hopper item " + first.getType() + " from " + drawer.getLocation());
            return false;
        }

        final int guardedAmount;
        try {
            guardedAmount = this.context.settings().automation().hopperTransfers().guardedAmountFor(
                    drawer.getWorld().getName(),
                    first.getAmount(),
                    DrawerItemIdentity.nativeMaxStackSize(first)
            );
        } catch (final DrawerInvariantViolationException | IllegalArgumentException exception) {
            debug("blocked invalid hopper stack from " + drawer.getLocation() + ": " + exception.getMessage());
            return false;
        }
        final ItemStack transferBatch = first.clone();
        transferBatch.setAmount(guardedAmount);
        return permitInsert(drawer, transferBatch);
    }

    /**
     * A plugin may replace InventoryMoveItemEvent's item after our guard has run. If the drawer had
     * no hidden stock and the resulting physical inventory is one uniform item type, the physical
     * barrel is the complete source of truth and can safely become the drawer template.
     */
    private Optional<DrawerState> adoptUniformPhysicalTemplate(
            final Barrel barrel,
            final DrawerState state,
            final long observedPhysicalCount
    ) {
        if (state.hiddenCount() != 0L || state.hasPendingProxyJournal() || observedPhysicalCount <= 0L) {
            return Optional.empty();
        }

        ItemStack exemplar = null;
        long counted = 0L;
        for (final ItemStack candidate : barrel.getInventory().getContents()) {
            if (!DrawerItemIdentity.isStorageCandidate(candidate)) {
                continue;
            }
            if (exemplar == null) {
                exemplar = DrawerItemIdentity.templateOf(candidate);
            } else if (!DrawerItemIdentity.matches(exemplar, candidate)) {
                return Optional.empty();
            }
            counted = Math.addExact(counted, candidate.getAmount());
        }
        if (exemplar == null || counted != observedPhysicalCount) {
            return Optional.empty();
        }

        final DrawerTierDefinition tier = this.context.tier(state.tierId()).orElse(null);
        if (tier == null) {
            return Optional.empty();
        }
        final long capacity = tier.tier().capacityFor(exemplar);
        if (counted > capacity) {
            return Optional.empty();
        }
        DrawerState repaired = DrawerState.empty(state.schemaVersion(), state.tierId(), capacity)
                .withTemplate(exemplar)
                .withDisplayLink(state.displayLink());
        if (repaired.usesReservedMirror()) {
            // Schema 2 treats storedTotal as authoritative. The physical hopper inventory is
            // the only source of the newly adopted stock, so record it in both coupled fields
            // before anything can render or reconcile the drawer.
            repaired = repaired.withStoredTotalAndExpectedMirrorCount(counted, counted);
        }
        if (!this.context.repository().save(barrel, repaired)) {
            return Optional.empty();
        }
        debug("adopted the observed hopper item as the template for drawer at " + barrel.getLocation());
        return Optional.of(repaired);
    }

    /**
     * Validates output without replacing the event item. Paper may have already removed it from
     * the source, and changing even an equivalent stack changes failure/restore semantics.
     */
    private boolean permitExtract(final Barrel barrel, final ItemStack item) {
        final Barrel current = this.context.blocks().barrel(barrel.getBlock()).orElse(null);
        if (current == null || !this.context.repository().isDrawer(current)) {
            debug("rejected output from " + barrel.getLocation() + " because the live drawer is unavailable");
            return false;
        }
        final DrawerStateReadResult read = this.context.repository().read(current);
        if (!(read instanceof DrawerStateReadResult.Valid valid)) {
            debug("rejected output from " + current.getLocation() + " because the drawer state is invalid");
            return false;
        }
        final DrawerBlockAccess.PhysicalStock observedPhysical = this.context.blocks().inspect(current, valid.state());
        if (!observedPhysical.matchesTemplate() || valid.state().hasPendingProxyJournal()) {
            debug("rejected output from " + current.getLocation() + " because physical stock mismatches="
                    + !observedPhysical.matchesTemplate() + " pendingJournal=" + valid.state().hasPendingProxyJournal());
            return false;
        }
        try {
            // Paper deliberately permits implementations to fire InventoryMoveItemEvent after the
            // source item was removed. Some transfer paths expose the pre-move source instead.
            // The event is therefore only a template/journal guard; its later region-owned
            // rebalance reconciles the actual physical mirror without guessing either timing. Do
            // not reject over-capacity physical stock here: draining it is the safe recovery path.
            if (item.getAmount() <= 0 || !valid.state().matchesTemplate(item)) {
                return false;
            }
            if (observedPhysical.count() < valid.state().expectedMirrorCount()) {
                if (item.getAmount() > valid.state().storedTotal()) {
                    debug("rejected already-applied output from " + current.getLocation()
                            + " because requested=" + item.getAmount() + " stored=" + valid.state().storedTotal());
                    return false;
                }
                debug("accepted already-applied output from " + current.getLocation()
                        + " physicalNow=" + observedPhysical.count());
                return true;
            }
            if (item.getAmount() > observedPhysical.count()) {
                debug("rejected output from " + current.getLocation() + " requested=" + item.getAmount()
                        + " available=" + observedPhysical.count());
                return false;
            }
            debug("accepted output from " + current.getLocation() + " requested=" + item.getAmount()
                    + " available=" + observedPhysical.count());
            return true;
        } catch (final DrawerInvariantViolationException | IllegalArgumentException exception) {
            debug("rejected output from " + current.getLocation() + " because " + exception.getMessage());
            return false;
        }
    }

    private static Barrel barrel(final Inventory inventory) {
        final InventoryHolder holder = inventory.getHolder(false);
        if (holder instanceof Barrel barrel) {
            return barrel;
        }
        // Some hopper transfer inventories expose no holder even though they still represent a
        // placed container. Resolve their location so both hopper input and output are observed,
        // reconciled, and rendered just like holder-backed inventories.
        final Location location = inventory.getLocation();
        if (location == null) {
            return null;
        }
        return location.getBlock().getState() instanceof Barrel barrel ? barrel : null;
    }

    private void debug(final String message) {
        if (this.context.settings().automation().debugLogging()) {
            this.context.logger().info("[Hopper debug] " + message);
        }
    }

    private boolean isActive() {
        return this.active.get();
    }

    private void observePhysical(final Barrel barrel, final long physicalCount) {
        final DrawerKey key = DrawerKey.of(barrel.getLocation());
        final WatchedDrawer watcher = this.watchedDrawers.get(key);
        if (watcher != null) {
            watcher.observe(physicalCount);
        }
    }

    private static String inventoryDescription(final Inventory inventory, final Barrel barrel, final boolean drawer) {
        final Location location = inventory.getLocation();
        return inventory.getType() + "@" + (location == null ? "unknown" : location)
                + " holder=" + (barrel == null ? "none" : barrel.getClass().getSimpleName())
                + " drawer=" + drawer;
    }

    private record DrawerKey(UUID worldId, int x, int y, int z) {
        static DrawerKey of(final Location location) {
            return new DrawerKey(Objects.requireNonNull(location.getWorld(), "world").getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
    }

    /** One identity-scoped reconciliation chain. Closing prevents unload/reload ABA races. */
    private static final class RebalanceWork {
        private boolean trailingPassRequested;
        private boolean closed;

        private synchronized boolean requestTrailingPass() {
            if (this.closed) {
                return false;
            }
            this.trailingPassRequested = true;
            return true;
        }

        private synchronized boolean finishPass() {
            if (this.closed) {
                return false;
            }
            if (this.trailingPassRequested) {
                this.trailingPassRequested = false;
                return true;
            }
            this.closed = true;
            return false;
        }
    }

    private static final class WatchedDrawer {
        private volatile long physicalCount;

        private WatchedDrawer(final long physicalCount) {
            this.physicalCount = physicalCount;
        }

        private long physicalCount() {
            return this.physicalCount;
        }

        private void observe(final long replacement) {
            this.physicalCount = replacement;
        }
    }
}
