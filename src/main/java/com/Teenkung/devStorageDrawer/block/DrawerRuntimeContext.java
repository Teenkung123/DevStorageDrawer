package com.Teenkung.devStorageDrawer.block;

import com.Teenkung.devStorageDrawer.api.DrawerChangeCause;
import com.Teenkung.devStorageDrawer.config.DrawerMessages;
import com.Teenkung.devStorageDrawer.config.DrawerSettings;
import com.Teenkung.devStorageDrawer.config.DrawerTierDefinition;
import com.Teenkung.devStorageDrawer.domain.DrawerState;
import com.Teenkung.devStorageDrawer.domain.DrawerStorageStrategy;
import com.Teenkung.devStorageDrawer.persistence.DrawerStateRepository;
import com.Teenkung.devStorageDrawer.scheduler.FoliaExecution;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.block.Barrel;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Shared mutable runtime snapshot. Settings/messages are atomically swapped on /drawers reload. */
public final class DrawerRuntimeContext {

    private final JavaPlugin plugin;
    private final FoliaExecution execution;
    private final DrawerStateRepository repository;
    private final DrawerStorageStrategy storage;
    private final DrawerRuntime.DrawerTierLookup tierLookup;
    private final DrawerRuntime.DrawerRenderer renderer;
    private final DrawerRuntime.DrawerParcelBreakHandler parcelBreakHandler;
    private final DrawerBlockAccess blocks;
    private volatile DrawerSettings settings;
    private volatile DrawerMessages messages;
    private volatile Consumer<DrawerMutation> mutationObserver = mutation -> { };

    DrawerRuntimeContext(
            final JavaPlugin plugin,
            final FoliaExecution execution,
            final DrawerStateRepository repository,
            final DrawerStorageStrategy storage,
            final DrawerSettings settings,
            final DrawerMessages messages,
            final DrawerRuntime.DrawerTierLookup tierLookup,
            final DrawerRuntime.DrawerRenderer renderer,
            final DrawerRuntime.DrawerParcelBreakHandler parcelBreakHandler
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.tierLookup = Objects.requireNonNull(tierLookup, "tierLookup");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.parcelBreakHandler = Objects.requireNonNull(parcelBreakHandler, "parcelBreakHandler");
        this.blocks = new DrawerBlockAccess(repository, tierLookup);
    }

    public JavaPlugin plugin() {
        return this.plugin;
    }

    public Logger logger() {
        return this.plugin.getLogger();
    }

    public FoliaExecution execution() {
        return this.execution;
    }

    public DrawerStateRepository repository() {
        return this.repository;
    }

    public DrawerStorageStrategy storage() {
        return this.storage;
    }

    public DrawerSettings settings() {
        return this.settings;
    }

    public DrawerMessages messages() {
        return this.messages;
    }

    public DrawerBlockAccess blocks() {
        return this.blocks;
    }

    public Optional<DrawerTierDefinition> tier(final String id) {
        return this.tierLookup.find(id);
    }

    public DrawerRuntime.DrawerParcelBreakHandler parcelBreakHandler() {
        return this.parcelBreakHandler;
    }

    public void reloadSettings(final DrawerSettings replacement) {
        this.settings = Objects.requireNonNull(replacement, "replacement");
    }

    public void reloadMessages(final DrawerMessages replacement) {
        this.messages = Objects.requireNonNull(replacement, "replacement");
    }

    void setMutationObserver(final Consumer<DrawerMutation> observer) {
        this.mutationObserver = Objects.requireNonNull(observer, "observer");
    }

    /** Must be called only after the supplied state has been durably saved. */
    public void notifyCommitted(final Barrel barrel, final DrawerState state, final long physicalProxyCount) {
        notifyCommitted(barrel, state, physicalProxyCount, DrawerChangeCause.SYSTEM);
    }

    /** Must be called only after the supplied state has been durably saved. */
    public void notifyCommitted(
            final Barrel barrel,
            final DrawerState state,
            final long physicalProxyCount,
            final DrawerChangeCause cause
    ) {
        this.renderer.refresh(barrel, state, physicalProxyCount);
        this.mutationObserver.accept(new DrawerMutation(barrel, state, physicalProxyCount, cause));
    }

    public void removeRender(final Barrel barrel, final DrawerState state) {
        this.renderer.remove(barrel, state);
    }

    public void removeRendersAt(final Barrel barrel) {
        this.renderer.removeAt(barrel);
    }

    public record DrawerMutation(Barrel barrel, DrawerState state, long physicalProxyCount, DrawerChangeCause cause) {
        public DrawerMutation {
            Objects.requireNonNull(barrel, "barrel");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(cause, "cause");
            if (physicalProxyCount < 0L) {
                throw new IllegalArgumentException("physicalProxyCount must not be negative");
            }
        }
    }
}
