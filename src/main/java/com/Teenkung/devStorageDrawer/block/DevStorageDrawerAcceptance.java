package com.Teenkung.devStorageDrawer.block;

import com.Teenkung.devStorageDrawer.config.DrawerTierDefinition;
import com.Teenkung.devStorageDrawer.display.DrawerVisualRenderer;
import com.Teenkung.devStorageDrawer.domain.DrawerDisplayLink;
import com.Teenkung.devStorageDrawer.domain.DrawerItemIdentity;
import com.Teenkung.devStorageDrawer.domain.DrawerJournalKind;
import com.Teenkung.devStorageDrawer.domain.DrawerJournalPhase;
import com.Teenkung.devStorageDrawer.domain.DrawerJournalReconciliation;
import com.Teenkung.devStorageDrawer.domain.DrawerProxyJournal;
import com.Teenkung.devStorageDrawer.domain.DrawerState;
import com.Teenkung.devStorageDrawer.domain.DrawerStorageTransaction;
import com.Teenkung.devStorageDrawer.domain.DrawerTier;
import com.Teenkung.devStorageDrawer.domain.DrawerWithdrawalPlan;
import com.Teenkung.devStorageDrawer.domain.SingleItemDrawerStorage;
import com.Teenkung.devStorageDrawer.hopper.DrawerHopperBridge;
import com.Teenkung.devStorageDrawer.parcel.DrawerParcelService;
import com.Teenkung.devStorageDrawer.persistence.DrawerStateCodec;
import com.Teenkung.devStorageDrawer.persistence.DrawerStateReadResult;
import com.Teenkung.devStorageDrawer.persistence.DrawerStateRepository;
import com.Teenkung.devStorageDrawer.redstone.DrawerComparatorService;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Hopper;
import org.bukkit.block.data.AnaloguePowerable;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Comparator;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/** Development-only acceptance rig enabled by {@code -Ddevstoragedrawer.acceptance=true}. */
public final class DevStorageDrawerAcceptance {
    public static final String ENABLE_PROPERTY = "devstoragedrawer.acceptance";
    public static final String PASS_MARKER = "DEVSTORAGEDRAWER_ACCEPTANCE:PASS";
    public static final String FAIL_MARKER = "DEVSTORAGEDRAWER_ACCEPTANCE:FAIL";

    private static final List<BlockFace> FACES = List.of(
            BlockFace.NORTH,
            BlockFace.SOUTH,
            BlockFace.EAST,
            BlockFace.WEST,
            BlockFace.UP,
            BlockFace.DOWN
    );

    private final JavaPlugin plugin;
    private final DrawerRuntimeContext context;
    private final DrawerStateRepository repository;
    private final DrawerVisualRenderer visuals;
    private final DrawerParcelService parcels;
    private final DrawerHopperBridge hoppers;
    private final List<Block> changedBlocks = new ArrayList<>();
    private final List<Entity> spawnedEntities = new ArrayList<>();
    private final Map<BlockFace, Location> displayDrawers = new EnumMap<>(
            BlockFace.class
    );
    private Location base;
    private Chunk acceptanceChunk;
    private HopperMinecart hopperMinecart;

    public DevStorageDrawerAcceptance(
            final JavaPlugin plugin,
            final DrawerRuntime runtime,
            final DrawerStateRepository repository,
            final DrawerVisualRenderer visuals,
            final DrawerParcelService parcels
    ) {
        this.plugin = plugin;
        this.context = runtime.acceptanceContext();
        this.repository = repository;
        this.visuals = visuals;
        this.parcels = parcels;
        this.hoppers = runtime.acceptanceHopperBridge();
    }

    public void start() {
        context.execution().runGlobal(task -> {
            final World world = Bukkit.getWorlds().stream().findFirst().orElseThrow();
            final Location spawn = world.getSpawnLocation();
            final int chunkX = (spawn.getBlockX() >> 4) + 8;
            final int chunkZ = (spawn.getBlockZ() >> 4) + 8;
            final int y = Math.min(world.getMaxHeight() - 12, Math.max(world.getMinHeight() + 12, spawn.getBlockY() + 24));
            base = new Location(world, chunkX << 4, y, chunkZ << 4);
            context.execution().executeAt(base, this::setup);
        });
    }

    private void setup() {
        try {
            acceptanceChunk = base.getChunk();
            require(acceptanceChunk.addPluginChunkTicket(plugin), "Could not keep the acceptance chunk loaded");
            runRegistryAndConservationTests();
            setupDisplays();
            setupHopperRigs();
            setupComparator();
            setupParcelGate();
            context.execution().runLaterAt(base, 240L, task -> verifyLiveRigs());
        } catch (final Throwable throwable) {
            fail(throwable);
        }
    }

    private void runRegistryAndConservationTests() {
        final ItemStack stone = new ItemStack(Material.STONE, 1);
        final ItemStack dirt = new ItemStack(Material.DIRT, 1);
        final SingleItemDrawerStorage storage = new SingleItemDrawerStorage();
        for (final DrawerTierDefinition definition : context.settings().tiers().values()) {
            final DrawerTier tier = definition.tier();
            DrawerState state = DrawerState.empty(tier.id(), tier.stackCapacity())
                    .initializeCapacityForFirstItem(tier, stone);
            DrawerStorageTransaction seed = storage.deposit(state, 0L, stone, 1L);
            require(seed.accepted(), "Seed deposit failed for " + tier.id());
            state = seed.stateAfter();
            for (int cycle = 0; cycle < 10_000; cycle++) {
                final DrawerStorageTransaction deposit = storage.deposit(state, 0L, stone, 1L);
                require(deposit.accepted(), "Conservation deposit failed for " + tier.id());
                final DrawerStorageTransaction withdrawal = storage.withdraw(deposit.stateAfter(), 0L, 1L);
                require(withdrawal.accepted(), "Conservation withdrawal failed for " + tier.id());
                state = withdrawal.stateAfter();
                require(state.totalForPhysical(0L) == 1L, "Conservation drifted for " + tier.id());
            }
            require(!storage.deposit(state, 0L, dirt, 1L).accepted(), "Wrong-item deposit was accepted");
            final DrawerState full = state.withHiddenCount(state.capacitySnapshot());
            require(!storage.deposit(full, 0L, stone, 1L).accepted(), "Full drawer accepted an item");
        }

        final DrawerState codecState = DrawerState.restored(
                DrawerState.CURRENT_SCHEMA_VERSION,
                "tier_1",
                stone,
                42L,
                2_048L,
                null,
                DrawerDisplayLink.none()
        );
        final DrawerStateCodec codec = new DrawerStateCodec();
        require(codecState.equals(codec.decode(codec.encode(codecState))), "Registry-backed state codec did not round-trip");
        require(DrawerItemIdentity.matches(stone, stone.clone()), "Item identity rejected an exact clone");

        final UUID operationId = UUID.randomUUID();
        final UUID ownerId = UUID.randomUUID();
        final DrawerProxyJournal prepared = new DrawerProxyJournal(
                operationId,
                DrawerJournalKind.PLAYER_WITHDRAWAL,
                DrawerJournalPhase.PREPARED,
                ownerId,
                10L,
                9L,
                0L,
                0L,
                1L
        );
        final DrawerState preparedState = DrawerState.restored(
                DrawerState.CURRENT_SCHEMA_VERSION,
                "tier_1",
                stone,
                10L,
                64L,
                prepared,
                DrawerDisplayLink.none()
        );
        require(
                storage.reconcile(preparedState, 0L).resolution() == DrawerJournalReconciliation.Resolution.ROLLED_BACK,
                "PREPARED hidden-only withdrawal did not roll back"
        );
        final DrawerState appliedState = DrawerState.restored(
                DrawerState.CURRENT_SCHEMA_VERSION,
                "tier_1",
                stone,
                9L,
                64L,
                prepared.withPhase(DrawerJournalPhase.APPLIED),
                DrawerDisplayLink.none()
        );
        final DrawerJournalReconciliation applied = storage.reconcile(appliedState, 0L);
        require(applied.resolution() == DrawerJournalReconciliation.Resolution.COMMITTED, "APPLIED withdrawal did not commit");
        require(applied.state().hiddenCount() == 9L, "APPLIED withdrawal recovered the wrong total");

        final DrawerState oneItem = DrawerState.restored(
                DrawerState.CURRENT_SCHEMA_VERSION,
                "tier_1",
                stone,
                1L,
                64L,
                null,
                DrawerDisplayLink.none()
        );
        final DrawerStorageTransaction finalItem = storage.withdraw(oneItem, 0L, 1L);
        final DrawerWithdrawalPlan finalItemPlan = DrawerWithdrawalPlan.forTransaction(
                finalItem,
                ownerId,
                2L
        );
        require(!finalItemPlan.committedState().hasTemplate(), "Final withdrawal did not clear its committed template");
        require(finalItemPlan.appliedJournaledState().hasTemplate(), "Final withdrawal journal lost its recovery template");
        final DrawerJournalReconciliation finalItemApplied = storage.reconcile(
                finalItemPlan.appliedJournaledState(),
                0L
        );
        require(finalItemApplied.resolution() == DrawerJournalReconciliation.Resolution.COMMITTED,
                "Final-item withdrawal journal did not commit");
        require(!finalItemApplied.state().hasTemplate(), "Final-item recovery left the empty drawer item-locked");
        plugin.getLogger().info("Acceptance: registry codecs and 10,000 conservation cycles per tier passed");
    }

    private void setupDisplays() {
        final DrawerTier tier = firstTier();
        final ItemStack stone = new ItemStack(Material.STONE, 1);
        for (int index = 0; index < FACES.size(); index++) {
            final BlockFace face = FACES.get(index);
            final Block block = block(1 + index, 6, 1);
            setType(block, Material.BARREL);
            final Directional directional = (Directional) block.getBlockData();
            directional.setFacing(face);
            block.setBlockData(directional, false);
            final Barrel barrel = (Barrel) block.getState();
            final DrawerState state = DrawerState.restored(
                    DrawerState.CURRENT_SCHEMA_VERSION,
                    tier.id(),
                    stone,
                    32L,
                    tier.capacityFor(stone),
                    null,
                    DrawerDisplayLink.none()
            );
            require(repository.save(barrel, state), "Could not save display acceptance drawer");
            visuals.refresh(barrel, state, 0L);
            displayDrawers.put(face, block.getLocation());
        }
    }

    private void setupHopperRigs() {
        final DrawerTier tier = firstTier();
        final ItemStack stone = new ItemStack(Material.STONE, 1);

        final Block inputDrawerBlock = block(1, 0, 5);
        final Block inputHopperBlock = block(1, 1, 5);
        final Block inputChestBlock = block(1, 2, 5);
        final Barrel inputDrawer = createDrawer(inputDrawerBlock, DrawerState.empty(tier.id(), tier.stackCapacity()));
        final Hopper inputHopper = createHopper(inputHopperBlock, BlockFace.DOWN);
        final Chest inputChest = createChest(inputChestBlock);
        inputChest.getInventory().setItem(0, new ItemStack(Material.STONE, 64));
        require(inputDrawer.getInventory().isEmpty() && inputHopper.getInventory().isEmpty(), "Input rig was not empty");

        final Block outputChestBlock = block(4, 0, 5);
        final Block outputHopperBlock = block(4, 1, 5);
        final Block outputDrawerBlock = block(4, 2, 5);
        createChest(outputChestBlock);
        createHopper(outputHopperBlock, BlockFace.DOWN);
        final DrawerState outputState = DrawerState.restored(
                DrawerState.CURRENT_SCHEMA_VERSION,
                tier.id(),
                stone,
                320L,
                tier.capacityFor(stone),
                null,
                DrawerDisplayLink.none()
        );
        final Barrel outputDrawer = createDrawer(outputDrawerBlock, outputState);
        hoppers.recoverAndRebalance(outputDrawer);

        final Block sideDrawerBlock = block(9, 1, 5);
        createDrawer(sideDrawerBlock, DrawerState.empty(tier.id(), tier.stackCapacity()));
        final Map<Location, BlockFace> sideHoppers = Map.of(
                block(8, 1, 5).getLocation(), BlockFace.EAST,
                block(10, 1, 5).getLocation(), BlockFace.WEST,
                block(9, 1, 4).getLocation(), BlockFace.SOUTH,
                block(9, 1, 6).getLocation(), BlockFace.NORTH
        );
        int index = 0;
        for (final Map.Entry<Location, BlockFace> entry : sideHoppers.entrySet()) {
            final Hopper hopper = createHopper(entry.getKey().getBlock(), entry.getValue());
            hopper.getInventory().setItem(0, new ItemStack(Material.STONE, 16));
            if (index++ == 0) {
                hopper.getInventory().setItem(1, new ItemStack(Material.DIRT, 1));
            }
        }

        setType(block(13, -1, 5), Material.STONE);
        final Block rail = block(13, 0, 5);
        final Block minecartDrawerBlock = block(13, 1, 5);
        setType(rail, Material.RAIL);
        final DrawerState minecartState = DrawerState.restored(
                DrawerState.CURRENT_SCHEMA_VERSION,
                tier.id(),
                stone,
                64L,
                tier.capacityFor(stone),
                null,
                DrawerDisplayLink.none()
        );
        final Barrel minecartDrawer = createDrawer(minecartDrawerBlock, minecartState);
        hoppers.recoverAndRebalance(minecartDrawer);
        for (final Entity nearby : base.getWorld().getNearbyEntities(
                rail.getLocation().add(0.5D, 0.5D, 0.5D),
                2.0D,
                2.0D,
                2.0D
        )) {
            if (nearby instanceof HopperMinecart
                    || nearby instanceof Item item && item.getItemStack().getType() == Material.STONE) {
                nearby.remove();
            }
        }
        hopperMinecart = base.getWorld().spawn(rail.getLocation().add(0.5D, 0.1D, 0.5D), HopperMinecart.class);
        hopperMinecart.setVelocity(new Vector());
        hopperMinecart.setGravity(false);
        require(hopperMinecart.getInventory().isEmpty(), "New acceptance hopper minecart was not empty");
        spawnedEntities.add(hopperMinecart);

        final Block drainChestBlock = block(1, 0, 14);
        final Block drainHopperBlock = block(1, 1, 14);
        final Block drainDrawerBlock = block(1, 2, 14);
        createChest(drainChestBlock);
        createHopper(drainHopperBlock, BlockFace.DOWN);
        final DrawerState drainState = DrawerState.restored(
                DrawerState.CURRENT_SCHEMA_VERSION,
                tier.id(),
                stone,
                5L,
                tier.capacityFor(stone),
                null,
                DrawerDisplayLink.none()
        );
        final Barrel drainDrawer = createDrawer(drainDrawerBlock, drainState);
        hoppers.recoverAndRebalance(drainDrawer);

        final Block countedDrawerBlock = block(4, 0, 14);
        final Block countedHopperBlock = block(4, 1, 14);
        final Block countedChestBlock = block(4, 2, 14);
        createDrawer(countedDrawerBlock, DrawerState.empty(tier.id(), tier.stackCapacity()));
        createHopper(countedHopperBlock, BlockFace.DOWN);
        final Chest countedChest = createChest(countedChestBlock);
        countedChest.getInventory().setItem(0, acceptanceModelItem(10));

        createDrawer(block(7, 0, 14), DrawerState.empty(tier.id(), tier.stackCapacity()));

        final int guardedBatch = context.settings().automation().hopperTransfers().guardedAmountFor(
                base.getWorld().getName(),
                16,
                stone.getMaxStackSize()
        );
        final long capacity = tier.capacityFor(stone);
        final Barrel nearFullDrawer = createDrawer(
                block(10, 0, 14),
                DrawerState.restored(
                        DrawerState.CURRENT_SCHEMA_VERSION,
                        tier.id(),
                        stone,
                        capacity - (guardedBatch - 1L),
                        capacity,
                        null,
                        DrawerDisplayLink.none()
                )
        );
        hoppers.recoverAndRebalance(nearFullDrawer);
        createHopper(block(10, 1, 14), BlockFace.DOWN);
        createChest(block(10, 2, 14)).getInventory().setItem(0, new ItemStack(Material.STONE, guardedBatch));

        final Barrel exactBatchDrawer = createDrawer(
                block(13, 0, 14),
                DrawerState.restored(
                        DrawerState.CURRENT_SCHEMA_VERSION,
                        tier.id(),
                        stone,
                        capacity - guardedBatch,
                        capacity,
                        null,
                        DrawerDisplayLink.none()
                )
        );
        hoppers.recoverAndRebalance(exactBatchDrawer);
        createHopper(block(13, 1, 14), BlockFace.DOWN);
        createChest(block(13, 2, 14)).getInventory().setItem(0, new ItemStack(Material.STONE, guardedBatch));
    }

    private void setupComparator() {
        final DrawerTier tier = firstTier();
        final ItemStack stone = new ItemStack(Material.STONE, 1);
        final long capacity = tier.capacityFor(stone);
        final Block drawerBlock = block(4, 1, 10);
        final Block comparatorBlock = block(5, 1, 10);
        final Block wireBlock = block(6, 1, 10);
        setType(block(5, 0, 10), Material.STONE);
        setType(block(6, 0, 10), Material.STONE);
        final DrawerState state = DrawerState.restored(
                DrawerState.CURRENT_SCHEMA_VERSION,
                tier.id(),
                stone,
                capacity / 2L,
                capacity,
                null,
                DrawerDisplayLink.none()
        );
        final Barrel drawer = createDrawer(drawerBlock, state);
        setType(comparatorBlock, Material.COMPARATOR);
        final Comparator comparator = (Comparator) comparatorBlock.getBlockData();
        comparator.setFacing(BlockFace.WEST);
        comparatorBlock.setBlockData(comparator, true);
        setType(wireBlock, Material.REDSTONE_WIRE);
        wireBlock.setBlockData(wireBlock.getBlockData(), true);
        // Production rebalancing shapes real proxy stock so vanilla's own barrel fullness formula
        // equals the logical drawer fullness without NMS or a synthetic redstone source.
        hoppers.recoverAndRebalance(drawer);
    }

    private void setupParcelGate() {
        final DrawerTierDefinition largest = context.settings().tiers().values().stream()
                .max(java.util.Comparator.comparingLong(definition -> definition.tier().stackCapacity()))
                .orElseThrow();
        final ItemStack stone = new ItemStack(Material.STONE, 1);
        final Location drop = block(1, 1, 10).getLocation().add(0.5D, 0.5D, 0.5D);
        final UUID owner = UUID.randomUUID();
        final Item drawerItem = drop.getWorld().dropItem(drop, largest.createItem());
        require(
                context.blocks().tierForPlacedItem(drawerItem.getItemStack())
                        .map(definition -> definition.tier().id().equals(largest.tier().id()))
                        .orElse(false),
                "Broken drawer item lost its tier placement tag"
        );
        final Item parcel = parcels.dropForBreak(owner, drop, stone, largest.tier().capacityFor(stone));
        spawnedEntities.add(drawerItem);
        spawnedEntities.add(parcel);
        require(owner.equals(parcel.getOwner()), "Break parcel was not owner-bound");
        require(parcel.getItemStack().getType() == Material.BUNDLE, "Break parcel did not use a bundle item");
        require(parcel.isCustomNameVisible(), "Break parcel hologram was not visible");
        require(!parcel.isUnlimitedLifetime(), "Break parcel did not use vanilla despawn aging");

        final Inventory source = Bukkit.createInventory(null, InventoryType.HOPPER);
        final Inventory destination = Bukkit.createInventory(null, 9);
        final InventoryMoveItemEvent move = new InventoryMoveItemEvent(source, parcel.getItemStack(), destination, true);
        Bukkit.getPluginManager().callEvent(move);
        require(move.isCancelled(), "Parcel was accepted by InventoryMoveItemEvent");
    }

    private void verifyLiveRigs() {
        try {
            verifyDisplays();
            verifyHoppers();
            verifyComparator();
            verifyParcelDrops();
            plugin.getLogger().info(PASS_MARKER);
            cleanup();
            shutdownLater();
        } catch (final Throwable throwable) {
            fail(throwable);
        }
    }

    private void verifyDisplays() {
        for (final Map.Entry<BlockFace, Location> entry : displayDrawers.entrySet()) {
            final Barrel barrel = (Barrel) entry.getValue().getBlock().getState();
            final DrawerStateReadResult read = repository.read(barrel);
            require(read instanceof DrawerStateReadResult.Valid, "Display drawer state became invalid");
            final DrawerDisplayLink link = ((DrawerStateReadResult.Valid) read).state().displayLink();
            require(link.javaItemDisplayId() != null, "Java item display is missing");
            require(link.javaNameTextDisplayId() != null && link.javaAmountTextDisplayId() != null, "Java text display is missing");
            require(link.bedrockItemDisplayId() != null && link.bedrockTextDisplayId() != null, "Bedrock fallback is missing");
            verifyFrontEntity(barrel, entry.getKey(), link.javaItemDisplayId(), ItemDisplay.class);
            verifyFrontEntity(barrel, entry.getKey(), link.javaNameTextDisplayId(), TextDisplay.class);
            verifyFrontEntity(barrel, entry.getKey(), link.javaAmountTextDisplayId(), TextDisplay.class);
            verifyFrontEntity(barrel, entry.getKey(), link.bedrockItemDisplayId(), ArmorStand.class);
            verifyFrontEntity(barrel, entry.getKey(), link.bedrockTextDisplayId(), ArmorStand.class);
            final Entity itemEntity = barrel.getWorld().getEntity(link.javaItemDisplayId());
            require(itemEntity instanceof ItemDisplay itemDisplay
                            && itemDisplay.getItemDisplayTransform() == context.settings().visuals().itemTransform()
                            && Math.abs(itemDisplay.getTransformation().getScale().x()
                            - context.settings().visuals().itemScale()) < 0.0001F
                            && Math.abs(itemDisplay.getTransformation().getScale().z()
                            - context.settings().visuals().itemDepthScale()) < 0.0001F,
                    "Java item display has the wrong presentation transform");
            final Entity nameEntity = barrel.getWorld().getEntity(link.javaNameTextDisplayId());
            require(nameEntity instanceof TextDisplay nameDisplay
                            && nameDisplay.getLineWidth() == context.settings().visuals().textLineWidth()
                            && Math.abs(nameDisplay.getTransformation().getScale().x()
                            - context.settings().visuals().textScale()) < 0.0001F,
                    "Java text display sizing is not configured correctly");
        }
        plugin.getLogger().info("Acceptance: all six Java/Bedrock display facings passed");
    }

    private void verifyFrontEntity(
            final Barrel barrel,
            final BlockFace face,
            final UUID entityId,
            final Class<? extends Entity> expectedType
    ) {
        final Entity entity = barrel.getWorld().getEntity(entityId);
        require(expectedType.isInstance(entity), "Display role has the wrong entity type");
        if (entity instanceof ArmorStand) {
            require(!entity.isVisibleByDefault(), "Bedrock fallback display is visible to Java by default");
        }
        final Location center = barrel.getLocation().add(0.5D, 0.5D, 0.5D);
        final double frontDistance;
        if (entity instanceof Display display) {
            final Location origin = entity.getLocation();
            require(origin.distanceSquared(center) < 0.01D, "Matrix display origin is not centered in its drawer");
            final var translation = display.getTransformation().getTranslation();
            frontDistance = translation.x() * face.getModX()
                    + translation.y() * face.getModY()
                    + translation.z() * face.getModZ();
        } else {
            final Location actual = entity.getLocation();
            frontDistance = (actual.getX() - center.getX()) * face.getModX()
                    + (actual.getY() - center.getY()) * face.getModY()
                    + (actual.getZ() - center.getZ()) * face.getModZ();
        }
        require(frontDistance > 0.45D, "Display entity is not in front of its " + face + " drawer");
        require(visuals.drawerLocation(entity).filter(barrel.getLocation()::equals).isPresent(), "Display ownership link is invalid");
    }

    private void verifyHoppers() {
        verifyConservationColumn(block(1, 0, 5), block(1, 1, 5), block(1, 2, 5), 64L, 0L);
        verifyConservationColumn(block(4, 2, 5), block(4, 1, 5), block(4, 0, 5), 320L, 320L);

        final Barrel sideDrawer = (Barrel) block(9, 1, 5).getState();
        final long sideDrawerTotal = drawerTotal(sideDrawer);
        long sideHopperStone = 0L;
        long sideHopperDirt = 0L;
        for (final Block block : List.of(block(8, 1, 5), block(10, 1, 5), block(9, 1, 4), block(9, 1, 6))) {
            final Inventory inventory = ((Hopper) block.getState()).getInventory();
            sideHopperStone += count(inventory, Material.STONE);
            sideHopperDirt += count(inventory, Material.DIRT);
        }
        require(sideDrawerTotal + sideHopperStone == 64L, "Concurrent side hoppers did not conserve stone");
        require(sideDrawerTotal > 0L, "Concurrent side hoppers never inserted into the drawer");
        require(sideHopperDirt == 1L, "Wrong-item side-hopper input was not rejected");

        final Barrel minecartDrawer = (Barrel) block(13, 1, 5).getState();
        final long minecartDrawerTotal = drawerTotal(minecartDrawer);
        final long minecartInventoryTotal = count(hopperMinecart.getInventory(), Material.STONE);
        require(minecartDrawerTotal + minecartInventoryTotal == 64L,
                "Hopper minecart did not conserve drawer stock: drawer=" + minecartDrawerTotal
                        + ", minecart=" + minecartInventoryTotal);
        require(minecartInventoryTotal > 0L, "Hopper minecart never extracted from the drawer");

        final Barrel drainDrawer = (Barrel) block(1, 2, 14).getState();
        final long drainedDrawerTotal = drawerTotal(drainDrawer);
        final long drainedOutput = count(((Hopper) block(1, 1, 14).getState()).getInventory(), Material.STONE)
                + count(((Chest) block(1, 0, 14).getState()).getInventory(), Material.STONE);
        require(drainedDrawerTotal == 0L && drainedOutput == 5L,
                "Low-stock output mirror did not replenish until empty: drawer=" + drainedDrawerTotal
                        + ", output=" + drainedOutput);

        final Barrel countedDrawer = (Barrel) block(4, 0, 14).getState();
        final long countedDrawerTotal = drawerTotal(countedDrawer);
        final long countedTransit = count(((Hopper) block(4, 1, 14).getState()).getInventory(), Material.PAPER)
                + count(((Chest) block(4, 2, 14).getState()).getInventory(), Material.PAPER);
        require(countedDrawerTotal == 10L && countedTransit == 0L,
                "Counted hopper input did not fully reconcile: drawer=" + countedDrawerTotal
                        + ", transit=" + countedTransit);
        final DrawerState countedState = ((DrawerStateReadResult.Valid) repository.read(countedDrawer)).state();
        final DrawerBlockAccess.PhysicalStock countedStock = context.blocks().inspect(countedDrawer, countedState);
        require(!countedState.hasPendingProxyJournal() && countedStock.matchesTemplate(),
                "Hopper input left the drawer unavailable for player interaction");
        final DrawerStorageTransaction interactionProbe = context.storage().withdraw(countedState, countedStock.count(), 1L);
        require(interactionProbe.accepted(), "A player-style transaction was rejected after hopper input");
        final Entity countedAmountEntity = countedDrawer.getWorld().getEntity(countedState.displayLink().javaAmountTextDisplayId());
        require(countedAmountEntity instanceof TextDisplay countedAmount
                        && Component.text("10 / " + countedState.capacitySnapshot()).equals(countedAmount.text()),
                "Hopper input did not update the visible drawer amount");

        final DrawerTier tier = firstTier();
        final ItemStack stone = new ItemStack(Material.STONE, 1);
        final DrawerState staleTemplate = DrawerState.restored(
                DrawerState.CURRENT_SCHEMA_VERSION,
                tier.id(),
                stone,
                0L,
                tier.capacityFor(stone),
                null,
                DrawerDisplayLink.none()
        );
        Barrel recoveryDrawer = (Barrel) block(7, 0, 14).getState();
        repository.save(recoveryDrawer, staleTemplate);
        recoveryDrawer.getInventory().setItem(0, acceptanceModelItem(3));
        hoppers.recoverAndRebalance(recoveryDrawer);
        recoveryDrawer = (Barrel) block(7, 0, 14).getState();
        final DrawerStateReadResult recoveredRead = repository.read(recoveryDrawer);
        require(recoveredRead instanceof DrawerStateReadResult.Valid, "Uniform physical proxy repair corrupted the drawer");
        final DrawerState recoveredState = ((DrawerStateReadResult.Valid) recoveredRead).state();
        final DrawerBlockAccess.PhysicalStock recoveredStock = context.blocks().inspect(recoveryDrawer, recoveredState);
        require(recoveredState.hasTemplate(),
                "Uniform repair lost its template: state=" + recoveredState + ", physical=" + recoveredStock.count());
        require(recoveredState.requireTemplate().getType() == Material.PAPER,
                "Uniform repair adopted the wrong item material: " + recoveredState.requireTemplate().getType());
        require(!recoveredState.hasPendingProxyJournal(), "Uniform repair left a pending journal");
        require(recoveredStock.matchesTemplate(), "Uniform repair left mismatched physical stock");
        require(recoveredState.totalForPhysical(recoveredStock.count()) == 3L,
                "Uniform repair did not conserve its three physical items");

        final int guardedBatch = context.settings().automation().hopperTransfers().guardedAmountFor(
                base.getWorld().getName(),
                16,
                stone.getMaxStackSize()
        );
        final long capacity = tier.capacityFor(stone);
        final Barrel nearFullDrawer = (Barrel) block(10, 0, 14).getState();
        final long nearFullTransit = count(((Hopper) block(10, 1, 14).getState()).getInventory(), Material.STONE)
                + count(((Chest) block(10, 2, 14).getState()).getInventory(), Material.STONE);
        final DrawerState nearFullState = ((DrawerStateReadResult.Valid) repository.read(nearFullDrawer)).state();
        final DrawerBlockAccess.PhysicalStock nearFullStock = context.blocks().inspect(nearFullDrawer, nearFullState);
        final long mirrorCapacity = 27L * stone.getMaxStackSize();
        require(drawerTotal(nearFullDrawer) == capacity - (guardedBatch - 1L)
                        && nearFullTransit == guardedBatch,
                "Near-full drawer accepted a partial configured hopper batch");
        require(mirrorCapacity - nearFullStock.count() == guardedBatch - 1L,
                "Near-full mirror exposed more physical space than its logical remaining capacity: physical="
                        + nearFullStock.count() + ", mirrorCapacity=" + mirrorCapacity);

        verifyOverflowRecoveryPlan(stone);

        final Barrel exactBatchDrawer = (Barrel) block(13, 0, 14).getState();
        final long exactBatchTransit = count(((Hopper) block(13, 1, 14).getState()).getInventory(), Material.STONE)
                + count(((Chest) block(13, 2, 14).getState()).getInventory(), Material.STONE);
        require(drawerTotal(exactBatchDrawer) == capacity && exactBatchTransit == 0L,
                "Drawer rejected a configured hopper batch that exactly fit its remaining capacity");
        plugin.getLogger().info("Acceptance: vanilla input/output, configured hopper batches, concurrent side hoppers, and hopper minecart passed");
    }

    private void verifyOverflowRecoveryPlan(final ItemStack template) {
        final DrawerState wedged = DrawerState.restored(
                DrawerState.CURRENT_SCHEMA_VERSION,
                "8",
                template,
                524_281L,
                1_605L,
                524_288L,
                null,
                DrawerDisplayLink.none()
        );
        final DrawerStorageTransaction recovery = DrawerStorageTransaction.recoverMirrorOverflow(wedged, 1_728L)
                .orElseThrow(() -> new IllegalStateException("Exact over-capacity NBT did not produce a recovery"));
        require(recovery.acceptedAmount() == 116L, "Overflow recovery returned the wrong item count");
        require(recovery.physicalAfter() == 1_612L, "Overflow recovery retained the wrong physical count");
        require(recovery.stateBefore().totalForPhysical(recovery.physicalBefore()) == 524_404L,
                "Overflow recovery lost the observed physical input");
        require(recovery.stateAfter().totalForPhysical(recovery.physicalAfter()) == 524_288L,
                "Overflow recovery did not normalize to the configured capacity");

        final DrawerWithdrawalPlan plan = DrawerWithdrawalPlan.forOverflowRecovery(
                recovery,
                UUID.randomUUID(),
                123L
        );
        require(plan.journal().kind() == DrawerJournalKind.OVERFLOW_RECOVERY,
                "Overflow recovery used the wrong durable journal kind");
        require(plan.committedState().capacitySnapshot() == 524_288L,
                "Overflow recovery left its temporary expanded capacity committed");
    }

    private void verifyConservationColumn(
            final Block drawerBlock,
            final Block hopperBlock,
            final Block chestBlock,
            final long expected,
            final long initialDrawerCount
    ) {
        final Barrel drawer = (Barrel) drawerBlock.getState();
        final Inventory hopper = ((Hopper) hopperBlock.getState()).getInventory();
        final Inventory chest = ((Chest) chestBlock.getState()).getInventory();
        final long drawerCount = drawerTotal(drawer);
        final long hopperCount = count(hopper, Material.STONE);
        final long chestCount = count(chest, Material.STONE);
        require(
                drawerCount + hopperCount + chestCount == expected,
                "Hopper column at " + drawerBlock.getLocation() + " did not conserve stock: drawer="
                        + drawerCount + ", hopper=" + hopperCount + ", chest=" + chestCount + ", expected=" + expected
        );
        require(drawerCount != initialDrawerCount || hopperCount > 0L || chestCount > 0L,
                "Hopper column never moved stock");
    }

    private void verifyComparator() {
        final Block drawerBlock = block(4, 1, 10);
        final Block comparatorBlock = block(5, 1, 10);
        final Barrel drawer = (Barrel) drawerBlock.getState();
        final DrawerState state = ((DrawerStateReadResult.Valid) repository.read(drawer)).state();
        final DrawerBlockAccess.PhysicalStock stock = context.blocks().inspect(drawer, state);
        require(stock.matchesTemplate(), "Comparator drawer proxy stock mismatched its template");
        final int expected = DrawerComparatorService.level(state, stock.count());
        comparatorBlock.tick();
        final Block wireBlock = block(6, 1, 10);
        final int livePower = wireBlock.getBlockData() instanceof AnaloguePowerable powerable ? powerable.getPower() : 0;
        require(
                livePower == expected,
                "Live comparator circuit produced " + livePower + " instead of " + expected
                        + "; proxy=" + stock.count()
                        + ", comparatorPowered=" + ((Comparator) comparatorBlock.getBlockData()).isPowered()
                        + ", comparatorBlockPower=" + comparatorBlock.getBlockPower()
                        + ", wireBlockPower=" + wireBlock.getBlockPower()
        );
        plugin.getLogger().info("Acceptance: live logical comparator circuit passed at level " + expected);
    }

    private void verifyParcelDrops() {
        final long survivingItems = spawnedEntities.stream()
                .filter(Item.class::isInstance)
                .filter(Entity::isValid)
                .count();
        require(survivingItems == 2L, "Full drawer recovery path retained " + survivingItems + " item entities instead of two");
        plugin.getLogger().info("Acceptance: parcel transport guard and two-item-entity break ceiling passed");
    }

    private Barrel createDrawer(final Block block, final DrawerState state) {
        setType(block, Material.BARREL);
        final Barrel barrel = (Barrel) block.getState();
        require(repository.save(barrel, state), "Could not save acceptance drawer state");
        hoppers.watch(barrel);
        return barrel;
    }

    private Hopper createHopper(final Block block, final BlockFace facing) {
        setType(block, Material.HOPPER);
        final org.bukkit.block.data.type.Hopper data = (org.bukkit.block.data.type.Hopper) block.getBlockData();
        data.setFacing(facing);
        data.setEnabled(true);
        block.setBlockData(data, false);
        return (Hopper) block.getState();
    }

    private Chest createChest(final Block block) {
        setType(block, Material.CHEST);
        return (Chest) block.getState();
    }

    private long drawerTotal(final Barrel barrel) {
        final DrawerStateReadResult read = repository.read(barrel);
        require(read instanceof DrawerStateReadResult.Valid, "Acceptance drawer state is invalid");
        final DrawerState state = ((DrawerStateReadResult.Valid) read).state();
        final DrawerBlockAccess.PhysicalStock stock = context.blocks().inspect(barrel, state);
        require(stock.matchesTemplate(), "Acceptance drawer proxy stock mismatched its template at "
                + barrel.getLocation() + ": stored=" + state.storedTotal() + ", expected="
                + state.expectedMirrorCount() + ", physical=" + stock.count());
        return state.totalForPhysical(stock.count());
    }

    private DrawerTier firstTier() {
        return context.settings().tiers().values().stream().findFirst().orElseThrow().tier();
    }

    private Block block(final int x, final int y, final int z) {
        return base.clone().add(x, y, z).getBlock();
    }

    private void setType(final Block block, final Material material) {
        if (!changedBlocks.contains(block)) {
            changedBlocks.add(block);
        }
        block.setType(material, false);
    }

    private static ItemStack acceptanceModelItem(final int amount) {
        final ItemStack item = new ItemStack(Material.PAPER, amount);
        final var meta = item.getItemMeta();
        meta.displayName(Component.text("Acceptance 3D Model"));
        item.setItemMeta(meta);
        return item;
    }

    private static long count(final Inventory inventory, final Material material) {
        long count = 0L;
        for (final ItemStack stack : inventory.getContents()) {
            if (stack != null && stack.getType() == material) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private void cleanup() {
        for (final Location location : displayDrawers.values()) {
            if (location.getBlock().getState() instanceof Barrel barrel) {
                final DrawerStateReadResult read = repository.read(barrel);
                if (read instanceof DrawerStateReadResult.Valid valid) {
                    visuals.remove(barrel, valid.state());
                }
            }
        }
        for (final Entity entity : spawnedEntities) {
            if (entity.isValid()) {
                entity.remove();
            }
        }
        for (final Block block : changedBlocks) {
            block.setType(Material.AIR, false);
        }
        if (acceptanceChunk != null) {
            acceptanceChunk.removePluginChunkTicket(plugin);
        }
    }

    private void fail(final Throwable throwable) {
        plugin.getLogger().log(Level.SEVERE, FAIL_MARKER + " " + throwable.getMessage(), throwable);
        try {
            cleanup();
        } catch (final RuntimeException cleanupFailure) {
            plugin.getLogger().log(Level.SEVERE, "Acceptance cleanup also failed", cleanupFailure);
        }
        shutdownLater();
    }

    private void shutdownLater() {
        context.execution().runLaterAt(base, 20L, task -> context.execution().runGlobal(ignored -> Bukkit.shutdown()));
    }
}
