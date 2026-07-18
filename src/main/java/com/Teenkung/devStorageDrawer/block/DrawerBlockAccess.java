package com.teenkung.devstoragedrawer.block;

import com.teenkung.devstoragedrawer.config.DrawerTierDefinition;
import com.teenkung.devstoragedrawer.domain.DrawerItemIdentity;
import com.teenkung.devstoragedrawer.domain.DrawerState;
import com.teenkung.devstoragedrawer.persistence.DrawerPdcKeys;
import com.teenkung.devstoragedrawer.persistence.DrawerStateReadResult;
import com.teenkung.devstoragedrawer.persistence.DrawerStateRepository;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Barrel identification and physical-proxy inspection, kept independent of event policy. */
public final class DrawerBlockAccess {

    private final DrawerStateRepository repository;
    private final DrawerRuntime.DrawerTierLookup tierLookup;

    DrawerBlockAccess(final DrawerStateRepository repository, final DrawerRuntime.DrawerTierLookup tierLookup) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.tierLookup = Objects.requireNonNull(tierLookup, "tierLookup");
    }

    public Optional<Barrel> barrel(final Block block) {
        if (block == null || block.getType() != Material.BARREL || !(block.getState() instanceof Barrel barrel)) {
            return Optional.empty();
        }
        return Optional.of(barrel);
    }

    public boolean isDrawer(final Block block) {
        return barrel(block).map(this.repository::isDrawer).orElse(false);
    }

    public DrawerStateReadResult read(final Barrel barrel) {
        return this.repository.read(Objects.requireNonNull(barrel, "barrel"));
    }

    public Optional<DrawerTierDefinition> tierForPlacedItem(final ItemStack item) {
        if (!DrawerItemIdentity.isStorageCandidate(item) || item.getType() != Material.BARREL) {
            return Optional.empty();
        }
        final ItemMeta meta = item.getItemMeta();
        final String id = meta.getPersistentDataContainer().get(DrawerPdcKeys.TIER_ID, PersistentDataType.STRING);
        return id == null ? Optional.empty() : this.tierLookup.find(id);
    }

    /**
     * Resolves a drawer tier from its raw block PDC, including drawers whose complete persisted
     * state can no longer be decoded. This lets the break-recovery path preserve the player's
     * configured drawer item while returning only the physical inventory that can be proven.
     */
    public Optional<DrawerTierDefinition> tierForDrawer(final Barrel barrel) {
        Objects.requireNonNull(barrel, "barrel");
        final String id = barrel.getPersistentDataContainer().get(DrawerPdcKeys.TIER_ID, PersistentDataType.STRING);
        return id == null ? Optional.empty() : this.tierLookup.find(id);
    }

    public Optional<DrawerTierDefinition> tierForState(final DrawerState state) {
        return this.tierLookup.find(Objects.requireNonNull(state, "state").tierId());
    }

    public PhysicalStock inspect(final Barrel barrel, final DrawerState state) {
        Objects.requireNonNull(barrel, "barrel");
        Objects.requireNonNull(state, "state");
        long count = 0L;
        boolean matches = true;
        for (final ItemStack item : barrel.getInventory().getContents()) {
            if (!DrawerItemIdentity.isStorageCandidate(item)) {
                continue;
            }
            try {
                count = Math.addExact(count, item.getAmount());
            } catch (final ArithmeticException exception) {
                return new PhysicalStock(Long.MAX_VALUE, false);
            }
            if (!state.hasTemplate() || !state.matchesTemplate(item)) {
                matches = false;
            }
        }
        return new PhysicalStock(count, matches);
    }

    public long count(final Inventory inventory) {
        long count = 0L;
        for (final ItemStack item : inventory.getContents()) {
            if (DrawerItemIdentity.isStorageCandidate(item)) {
                count = Math.addExact(count, item.getAmount());
            }
        }
        return count;
    }

    public record PhysicalStock(long count, boolean matchesTemplate) {
        public PhysicalStock {
            if (count < 0L) {
                throw new IllegalArgumentException("count must not be negative");
            }
        }
    }
}
