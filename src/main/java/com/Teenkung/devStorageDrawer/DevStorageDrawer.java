package com.teenkung.devstoragedrawer;

import com.teenkung.devstoragedrawer.api.DevStorageDrawerApi;
import com.teenkung.devstoragedrawer.bedrock.BedrockPlayerDetector;
import com.teenkung.devstoragedrawer.block.DrawerRuntime;
import com.teenkung.devstoragedrawer.block.DrawerSellWandBridge;
import com.teenkung.devstoragedrawer.block.DevStorageDrawerAcceptance;
import com.teenkung.devstoragedrawer.block.DevStorageDrawerRestartAcceptance;
import com.teenkung.devstoragedrawer.command.DrawerAdminFacade;
import com.teenkung.devstoragedrawer.command.DrawersCommand;
import com.teenkung.devstoragedrawer.config.DrawerConfigurationService;
import com.teenkung.devstoragedrawer.config.DrawerMessages;
import com.teenkung.devstoragedrawer.config.DrawerSettings;
import com.teenkung.devstoragedrawer.display.DrawerVisualRenderer;
import com.teenkung.devstoragedrawer.domain.DrawerState;
import com.teenkung.devstoragedrawer.domain.SingleItemDrawerStorage;
import com.teenkung.devstoragedrawer.parcel.DrawerParcelListener;
import com.teenkung.devstoragedrawer.parcel.DrawerParcelService;
import com.teenkung.devstoragedrawer.persistence.DrawerStateRepository;
import com.teenkung.devstoragedrawer.receipt.WithdrawalReceiptStore;
import com.teenkung.devstoragedrawer.scheduler.FoliaExecution;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.block.Barrel;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** Main plugin lifecycle and explicit integration boundary for all drawer subsystems. */
public final class DevStorageDrawer extends JavaPlugin {
    private DrawerConfigurationService configuration;
    private DrawerRuntime runtime;
    private DrawerVisualRenderer visuals;
    private DrawerParcelService parcels;
    private BedrockPlayerDetector bedrockPlayers;
    private WithdrawalReceiptStore withdrawalReceipts;
    private SingleItemDrawerStorage storage;
    private DevStorageDrawerApi api;

    @Override
    public void onEnable() {
        try {
            configuration = new DrawerConfigurationService(this);
            configuration.load();
        } catch (final RuntimeException exception) {
            getLogger().severe("Unable to load DevStorageDrawer configuration: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        final PluginManager pluginManager = getServer().getPluginManager();
        bedrockPlayers = BedrockPlayerDetector.create(pluginManager);
        if (!validateBedrockRequirement(configuration.settings(), pluginManager)) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        final FoliaExecution execution = new FoliaExecution(this);
        final DrawerStateRepository repository = new DrawerStateRepository();
        withdrawalReceipts = new WithdrawalReceiptStore(getDataFolder().toPath().resolve("withdrawal-receipts.dat"));
        try {
            withdrawalReceipts.load();
        } catch (final IOException exception) {
            getLogger().severe("Unable to load withdrawal receipts safely: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        parcels = new DrawerParcelService();
        storage = new SingleItemDrawerStorage(configuration.settings().redstone().logicalComparatorOutput());
        visuals = new DrawerVisualRenderer(this, execution, repository, bedrockPlayers, configuration.settings());
        runtime = new DrawerRuntime(
                this,
                execution,
                repository,
                storage,
                configuration.settings(),
                configuration.messages(),
                configuration::tier,
                new DrawerRuntime.DrawerRenderer() {
                    @Override
                    public void refresh(final Barrel barrel, final DrawerState state, final long physicalProxyCount) {
                        visuals.refresh(barrel, state, physicalProxyCount);
                    }

                    @Override
                    public void remove(final Barrel barrel, final DrawerState state) {
                        visuals.remove(barrel, state);
                    }

                    @Override
                    public void removeAt(final Barrel barrel) {
                        visuals.removeAt(barrel);
                    }
                },
                (owner, location, template, total) -> parcels.dropForBreak(owner, location, template, total),
                withdrawalReceipts,
                entity -> resolveDisplayTarget(entity, repository)
        );
        api = runtime.api();
        getServer().getServicesManager().register(DevStorageDrawerApi.class, api, this, ServicePriority.Normal);

        runtime.listeners().forEach(listener -> pluginManager.registerEvents(listener, this));
        pluginManager.registerEvents(visuals, this);
        pluginManager.registerEvents(new DrawerParcelListener(parcels, () -> configuration.messages()), this);
        registerCommand();
        runtime.repairLoadedDrawers();
        getLogger().info("DevStorageDrawer enabled with " + configuration.settings().tiers().size() + " configured tier(s).");
        final String restartAcceptancePhase = System.getProperty(DevStorageDrawerRestartAcceptance.PHASE_PROPERTY, "");
        if (!restartAcceptancePhase.isBlank()) {
            getLogger().warning("Development restart acceptance phase " + restartAcceptancePhase + " is enabled.");
            new DevStorageDrawerRestartAcceptance(this, runtime, repository, visuals).start(restartAcceptancePhase);
        } else if (Boolean.getBoolean(DevStorageDrawerAcceptance.ENABLE_PROPERTY)) {
            getLogger().warning("Development acceptance mode is enabled; an isolated live rig will run and stop the server.");
            new DevStorageDrawerAcceptance(this, runtime, repository, visuals, parcels).start();
        }
    }

    @Override
    public void onDisable() {
        if (runtime != null) {
            runtime.disable();
        }
        if (api != null) {
            getServer().getServicesManager().unregister(DevStorageDrawerApi.class, api);
            api = null;
        }
        // Drawer state is written synchronously on every mutation. Display entities remain persistent
        // and are reconciled through their PDC link on the next enable/chunk load.
        getLogger().info("DevStorageDrawer disabled.");
    }

    /** Stable public integration service. It is available after this plugin has enabled. */
    public DevStorageDrawerApi getApi() {
        if (api == null) {
            throw new IllegalStateException("DevStorageDrawer API is unavailable while the plugin is disabled");
        }
        return api;
    }

    /**
     * @deprecated Use {@link #getApi()} or Bukkit's ServicesManager. The bridge requires callers
     *             to manage Folia region ownership themselves and is kept only for compatibility.
     */
    @Deprecated(since = "1.0.0")
    public DrawerSellWandBridge getSellWandBridge() {
        return runtime == null ? null : runtime.sellWandBridge();
    }

    private void registerCommand() {
        final PluginCommand command = Objects.requireNonNull(getCommand("drawers"), "drawers command missing from plugin.yml");
        final DrawersCommand executor = new DrawersCommand(configuration, new DrawerAdminFacade() {
            @Override
            public void reload(final DrawerSettings settings, final DrawerMessages messages) {
                if (!validateBedrockRequirement(settings, getServer().getPluginManager())) {
                    throw new IllegalArgumentException("Bedrock renderer requires Floodgate on this Paper server");
                }
                storage.setLogicalComparatorProxy(settings.redstone().logicalComparatorOutput());
                visuals.reloadSettings(settings);
                runtime.reloadSettings(settings);
                runtime.reloadMessages(messages);
            }

            @Override
            public void repairLoadedDrawers() {
                runtime.repairLoadedDrawers();
            }

            @Override
            public void migrateTargetedDrawer(final Player player) {
                runtime.migrateTargetedDrawer(player);
            }
        });
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private Optional<Barrel> resolveDisplayTarget(final Entity entity, final DrawerStateRepository repository) {
        return visuals.drawerLocation(entity)
                .filter(location -> location.getBlock().getType() == Material.BARREL)
                .flatMap(location -> location.getBlock().getState() instanceof Barrel barrel && repository.isDrawer(barrel)
                        ? Optional.of(barrel)
                        : Optional.empty());
    }

    private boolean validateBedrockRequirement(final DrawerSettings settings, final PluginManager pluginManager) {
        if (!settings.bedrock().enabled() || !settings.bedrock().required()) {
            return true;
        }
        final boolean floodgate = pluginManager.isPluginEnabled("floodgate");
        if (floodgate && bedrockPlayers.isAvailable()) {
            return true;
        }
        getLogger().severe("Bedrock renderer is required but the backend Floodgate API is unavailable. "
                + "Install or enable Floodgate on this Paper server, or set bedrock.required: false. "
                + "Geyser may run on the proxy and does not need to be installed on this backend.");
        return false;
    }
}
