package com.teenkung.devstoragedrawer.parcel;

import com.teenkung.devstoragedrawer.config.DrawerMessages;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Blocks every automation/container path for parcels while allowing their owner to claim them. */
public final class DrawerParcelListener implements Listener {
    private final DrawerParcelService parcels;
    private final Supplier<DrawerMessages> messages;

    public DrawerParcelListener(final DrawerParcelService parcels, final Supplier<DrawerMessages> messages) {
        this.parcels = Objects.requireNonNull(parcels, "parcels");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPickup(final EntityPickupItemEvent event) {
        final Item itemEntity = event.getItem();
        final DrawerParcelData data = parcels.read(itemEntity.getItemStack()).orElse(null);
        if (data == null) {
            return;
        }
        // The world Bundle is only a recovery parcel representation. It must never enter an
        // inventory, otherwise it becomes an unlimited portable drawer.
        event.setCancelled(true);
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!data.ownerId().equals(player.getUniqueId())) {
            player.sendMessage(messages.get().component("parcel.owner-only", Map.of("owner", data.ownerId())));
            return;
        }
        final ItemStack parcel = itemEntity.getItemStack();
        final DrawerParcelService.ClaimResult result = parcels.claim(player, parcel);
        switch (result.status()) {
            case CLAIMED -> {
                if (result.exhausted()) {
                    itemEntity.remove();
                } else {
                    // Item#getItemStack is not specified as a mutable backing reference.
                    itemEntity.setItemStack(parcel);
                    parcels.configureDroppedParcel(itemEntity);
                }
                player.sendMessage(messages.get().component("parcel.claimed", Map.of("amount", result.moved())));
            }
            case OWNER_ONLY -> player.sendMessage(messages.get().component(
                    "parcel.owner-only", Map.of("owner", result.ownerId())
            ));
            case INVENTORY_FULL -> player.sendMessage(messages.get().component("parcel.inventory-full"));
            case NOT_PARCEL -> {
                // The item changed after this event was scheduled; cancellation is still safest.
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHopperPickup(final InventoryPickupItemEvent event) {
        if (parcels.isParcel(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(final ItemSpawnEvent event) {
        parcels.configureDroppedParcel(event.getEntity());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemMerge(final ItemMergeEvent event) {
        if (parcels.isParcel(event.getEntity().getItemStack())
                || parcels.isParcel(event.getTarget().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(final InventoryMoveItemEvent event) {
        if (parcels.isParcel(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(final InventoryClickEvent event) {
        final boolean currentParcel = parcels.isParcel(event.getCurrentItem());
        final boolean cursorParcel = parcels.isParcel(event.getCursor());
        final Player player = event.getWhoClicked() instanceof Player clickedPlayer ? clickedPlayer : null;
        final int hotbarButton = event.getHotbarButton();
        final ItemStack hotbarItem = hotbarButton < 0 || player == null
                ? null
                : player.getInventory().getItem(hotbarButton);
        final ItemStack offHandItem = player != null && event.getClick() == ClickType.SWAP_OFFHAND
                ? player.getInventory().getItemInOffHand()
                : null;
        final boolean swappedParcel = parcels.isParcel(hotbarItem) || parcels.isParcel(offHandItem);
        if (!currentParcel && !cursorParcel && !swappedParcel) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(final InventoryDragEvent event) {
        if (!parcels.isParcel(event.getOldCursor())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onParcelUse(final PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        final ItemStack item = event.getItem();
        if (!parcels.isParcel(item)) {
            return;
        }
        event.setCancelled(true);
        final DrawerParcelService.ClaimResult result = parcels.claim(event.getPlayer(), item);
        switch (result.status()) {
            case CLAIMED -> {
                if (result.exhausted()) {
                    setHeldItem(event.getPlayer(), event.getHand(), null);
                } else {
                    setHeldItem(event.getPlayer(), event.getHand(), item);
                }
                event.getPlayer().sendMessage(messages.get().component("parcel.claimed", Map.of("amount", result.moved())));
            }
            case OWNER_ONLY -> event.getPlayer().sendMessage(messages.get().component(
                    "parcel.owner-only", Map.of("owner", result.ownerId())
            ));
            case INVENTORY_FULL -> event.getPlayer().sendMessage(messages.get().component("parcel.inventory-full"));
            case NOT_PARCEL -> {
                // Nothing to do.
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onParcelDrop(final PlayerDropItemEvent event) {
        final ItemStack item = event.getItemDrop().getItemStack();
        final DrawerParcelData data = parcels.read(item).orElse(null);
        if (data != null && !data.ownerId().equals(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        } else if (data != null) {
            parcels.configureDroppedParcel(event.getItemDrop());
        }
    }

    private static void setHeldItem(final Player player, final EquipmentSlot hand, final ItemStack item) {
        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(item);
        } else {
            player.getInventory().setItemInMainHand(item);
        }
    }
}
