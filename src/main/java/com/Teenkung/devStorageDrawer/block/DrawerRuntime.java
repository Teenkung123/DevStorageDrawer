package com.Teenkung.devStorageDrawer.block;

import com.Teenkung.devStorageDrawer.api.DevStorageDrawerApi;
import com.Teenkung.devStorageDrawer.interaction.DrawerWithdrawalProtection;
import com.Teenkung.devStorageDrawer.api.DrawerChangeCause;
import com.Teenkung.devStorageDrawer.config.DrawerMessages;
import com.Teenkung.devStorageDrawer.config.DrawerSettings;
import com.Teenkung.devStorageDrawer.config.DrawerTierDefinition;
import com.Teenkung.devStorageDrawer.domain.DrawerInvariantViolationException;
import com.Teenkung.devStorageDrawer.domain.DrawerState;
import com.Teenkung.devStorageDrawer.domain.DrawerStorageStrategy;
import com.Teenkung.devStorageDrawer.hopper.DrawerHopperBridge;
import com.Teenkung.devStorageDrawer.interaction.DrawerInteractionListener;
import com.Teenkung.devStorageDrawer.persistence.DrawerStateReadResult;
import com.Teenkung.devStorageDrawer.persistence.DrawerStateRepository;
import com.Teenkung.devStorageDrawer.redstone.DrawerComparatorService;
import com.Teenkung.devStorageDrawer.receipt.DrawerWithdrawalCoordinator;
import com.Teenkung.devStorageDrawer.receipt.WithdrawalReceiptStore;
import com.Teenkung.devStorageDrawer.scheduler.FoliaExecution;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Public wiring point for the live barrel runtime.  It deliberately accepts small callbacks for
 * display and parcel handling so those services remain independent of interaction and hopper
 * code.
 */
public final class DrawerRuntime {

    private final DrawerRuntimeContext context;
    private final DrawerBlockListener blockListener;
    private final DrawerInteractionListener interactionListener;
    private final DrawerHopperBridge hopperBridge;
    private final DrawerComparatorService comparatorService;
    private final DrawerWithdrawalCoordinator withdrawalCoordinator;
    private final DrawerSellWandBridge sellWandBridge;
    private final DrawerApiService api;

    public DrawerRuntime(
            final JavaPlugin plugin,
            final FoliaExecution execution,
            final DrawerStateRepository repository,
            final DrawerStorageStrategy storage,
            final DrawerSettings settings,
            final DrawerMessages messages,
            final DrawerTierLookup tierLookup,
            final DrawerRenderer renderer,
            final DrawerParcelBreakHandler parcelBreakHandler,
            final WithdrawalReceiptStore receiptStore
    ) {
        this(
                plugin,
                execution,
                repository,
                storage,
                settings,
                messages,
                tierLookup,
                renderer,
                parcelBreakHandler,
                receiptStore,
                DrawerDisplayTargetResolver.none(),
                DrawerWithdrawalProtection.ALLOW_ALL
        );
    }

    public DrawerRuntime(
            final JavaPlugin plugin,
            final FoliaExecution execution,
            final DrawerStateRepository repository,
            final DrawerStorageStrategy storage,
            final DrawerSettings settings,
            final DrawerMessages messages,
            final DrawerTierLookup tierLookup,
            final DrawerRenderer renderer,
            final DrawerParcelBreakHandler parcelBreakHandler,
            final WithdrawalReceiptStore receiptStore,
            final DrawerDisplayTargetResolver displayTargetResolver
    ) {
        this(
                plugin, execution, repository, storage, settings, messages, tierLookup, renderer, parcelBreakHandler, receiptStore,
                displayTargetResolver, DrawerWithdrawalProtection.ALLOW_ALL
        );
    }

    public DrawerRuntime(
            final JavaPlugin plugin,
            final FoliaExecution execution,
            final DrawerStateRepository repository,
            final DrawerStorageStrategy storage,
            final DrawerSettings settings,
            final DrawerMessages messages,
            final DrawerTierLookup tierLookup,
            final DrawerRenderer renderer,
            final DrawerParcelBreakHandler parcelBreakHandler,
            final WithdrawalReceiptStore receiptStore,
            final DrawerDisplayTargetResolver displayTargetResolver,
            final DrawerWithdrawalProtection withdrawalProtection
    ) {
        Objects.requireNonNull(plugin, "plugin");
        this.context = new DrawerRuntimeContext(
                plugin,
                execution,
                repository,
                storage,
                settings,
                messages,
                tierLookup,
                renderer,
                parcelBreakHandler
        );
        this.comparatorService = new DrawerComparatorService(this.context);
        this.withdrawalCoordinator = new DrawerWithdrawalCoordinator(this.context, receiptStore);
        this.hopperBridge = new DrawerHopperBridge(this.context, this.withdrawalCoordinator);
        this.withdrawalCoordinator.setRebalanceRequester(this.hopperBridge::queueRebalance);
        this.sellWandBridge = new DrawerSellWandBridge(this.context, this.hopperBridge);
        this.api = new DrawerApiService(this.context, this.hopperBridge, this.sellWandBridge);
        this.context.setMutationObserver(mutation -> {
            this.comparatorService.onDrawerChanged(mutation);
            this.api.onCommitted(mutation);
        });
        this.interactionListener = new DrawerInteractionListener(
                this.context,
                this.hopperBridge,
                this.withdrawalCoordinator,
                displayTargetResolver,
                withdrawalProtection
        );
        this.blockListener = new DrawerBlockListener(this.context, this.hopperBridge);
    }

    /** Listener instances to register with Bukkit's plugin manager. */
    public List<Listener> listeners() {
        return List.of(
                this.blockListener,
                this.interactionListener,
                this.hopperBridge,
                this.comparatorService,
                this.withdrawalCoordinator
        );
    }

    public void reloadSettings(final DrawerSettings settings) {
        this.context.reloadSettings(settings);
    }

    public void reloadMessages(final DrawerMessages messages) {
        this.context.reloadMessages(messages);
    }

    /** Optional third-party bridge for selling the logical contents of a tagged drawer. */
    public DrawerSellWandBridge sellWandBridge() {
        return this.sellWandBridge;
    }

    /** Stable public integration contract. Internal runtime types remain private implementation details. */
    public DevStorageDrawerApi api() {
        return this.api;
    }

    /** Stops this runtime before the plugin scheduler is torn down. */
    public void disable() {
        this.hopperBridge.shutdown();
        this.api.disable();
    }

    /** Queues repair/reconciliation for every currently loaded tagged barrel. */
    public void repairLoadedDrawers() {
        this.blockListener.repairLoadedDrawers();
    }

    DrawerRuntimeContext acceptanceContext() {
        return this.context;
    }

    DrawerHopperBridge acceptanceHopperBridge() {
        return this.hopperBridge;
    }

    /** Explicitly adopts the current configured tier capacity for the drawer a player is looking at. */
    public void migrateTargetedDrawer(final Player player) {
        final Block target = player.getTargetBlockExact(6);
        if (target == null) {
            messagePlayer(player, "command.migrate-no-target", Map.of());
            return;
        }
        final Location location = target.getLocation();
        this.context.execution().executeAt(location, () -> {
            final Barrel barrel = this.context.blocks().barrel(location.getBlock()).orElse(null);
            if (barrel == null || !this.context.repository().isDrawer(barrel)) {
                messagePlayer(player, "command.migrate-no-target", Map.of());
                return;
            }
            final DrawerStateReadResult read = this.context.repository().read(barrel);
            if (!(read instanceof DrawerStateReadResult.Valid valid)) {
                messagePlayer(player, "general.configuration-error", Map.of());
                return;
            }
            final DrawerState state = valid.state();
            final DrawerBlockAccess.PhysicalStock stock = this.context.blocks().inspect(barrel, state);
            if (!stock.matchesTemplate() || state.hasPendingProxyJournal()) {
                this.hopperBridge.queueRebalance(barrel);
                messagePlayer(player, "general.configuration-error", Map.of());
                return;
            }
            final DrawerTierDefinition tier = this.context.tier(state.tierId()).orElse(null);
            if (tier == null) {
                messagePlayer(player, "general.configuration-error", Map.of());
                return;
            }
            try {
                final long total = state.totalForPhysical(stock.count());
                final long replacementCapacity = state.hasTemplate()
                        ? tier.tier().capacityFor(state.requireTemplate())
                        : tier.tier().stackCapacity();
                if (replacementCapacity < total) {
                    messagePlayer(player, "command.migrate-too-small", Map.of(
                            "stored", total,
                            "capacity", replacementCapacity
                    ));
                    return;
                }
                final DrawerState migrated = state.withCapacitySnapshot(replacementCapacity);
                if (!this.context.repository().save(barrel, migrated)) {
                    messagePlayer(player, "general.configuration-error", Map.of());
                    return;
                }
                this.context.notifyCommitted(barrel, migrated, stock.count(), DrawerChangeCause.MIGRATION);
                messagePlayer(player, "command.migrated", Map.of(
                        "old", state.capacitySnapshot(),
                        "capacity", replacementCapacity
                ));
            } catch (final DrawerInvariantViolationException | IllegalArgumentException exception) {
                messagePlayer(player, "general.configuration-error", Map.of());
            }
        });
    }

    private void messagePlayer(final Player player, final String key, final Map<String, ?> placeholders) {
        this.context.execution().runOnEntity(
                player,
                ignored -> player.sendMessage(this.context.messages().component(key, placeholders)),
                () -> { }
        );
    }

    @FunctionalInterface
    public interface DrawerTierLookup {
        Optional<DrawerTierDefinition> find(String tierId);
    }

    /** Called after the final drawer state has been persisted; implementations may update its PDC display link. */
    @FunctionalInterface
    public interface DrawerRenderer {
        void refresh(Barrel barrel, DrawerState state, long physicalProxyCount);

        default void remove(final Barrel barrel, final DrawerState state) {
            // Display support is optional at the runtime boundary.
        }

        default void removeAt(final Barrel barrel) {
            // Corrupt drawers may not have a readable state or display link.
        }

        static DrawerRenderer none() {
            return (barrel, state, physicalProxyCount) -> { };
        }
    }

    /** Creates one owner-bound parcel rather than a large item-entity flood when a drawer breaks. */
    @FunctionalInterface
    public interface DrawerParcelBreakHandler {
        void dropContents(Player owner, Location location, ItemStack template, long totalCount);

        static DrawerParcelBreakHandler none() {
            return (owner, location, template, totalCount) -> { };
        }
    }

    /** Lets the display subsystem map its entities back to their owning barrel for direct clicks. */
    @FunctionalInterface
    public interface DrawerDisplayTargetResolver {
        Optional<Barrel> resolve(Entity entity);

        static DrawerDisplayTargetResolver none() {
            return entity -> Optional.empty();
        }
    }
}
