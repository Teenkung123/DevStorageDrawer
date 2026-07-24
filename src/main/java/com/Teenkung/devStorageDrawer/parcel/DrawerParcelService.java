package com.Teenkung.devStorageDrawer.parcel;

import com.Teenkung.devStorageDrawer.domain.DrawerItemIdentity;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** Creates, decodes, and safely claims the one-item recovery parcel used for filled drawers. */
public final class DrawerParcelService {
    private static final NamespacedKey MARKER = new NamespacedKey("devstoragedrawer", "contents_parcel");
    private static final NamespacedKey PARCEL_ID = new NamespacedKey("devstoragedrawer", "parcel_id");
    private static final NamespacedKey OWNER_ID = new NamespacedKey("devstoragedrawer", "parcel_owner");
    private static final NamespacedKey TEMPLATE = new NamespacedKey("devstoragedrawer", "parcel_template");
    private static final NamespacedKey COUNT = new NamespacedKey("devstoragedrawer", "parcel_count");

    public ItemStack createParcel(final UUID ownerId, final ItemStack template, final long count) {
        if (count < 1L) {
            throw new IllegalArgumentException("A parcel must contain at least one item");
        }
        final ItemStack normalized = DrawerItemIdentity.templateOf(template);
        final ItemStack parcel = new ItemStack(Material.BUNDLE);
        write(parcel, new DrawerParcelData(UUID.randomUUID(), ownerId, normalized, count));
        return parcel;
    }

    public Optional<DrawerParcelData> read(final ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return Optional.empty();
        }
        try {
            final PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
            final Byte marker = pdc.get(MARKER, PersistentDataType.BYTE);
            if (marker == null || marker != (byte) 1) {
                return Optional.empty();
            }
            final String parcelId = pdc.get(PARCEL_ID, PersistentDataType.STRING);
            final String ownerId = pdc.get(OWNER_ID, PersistentDataType.STRING);
            final byte[] bytes = pdc.get(TEMPLATE, PersistentDataType.BYTE_ARRAY);
            final Long count = pdc.get(COUNT, PersistentDataType.LONG);
            if (parcelId == null || ownerId == null || bytes == null || count == null) {
                return Optional.empty();
            }
            final ItemStack template = ItemStack.deserializeBytes(bytes);
            DrawerItemIdentity.requireStorageCandidate(template, "Parcel template");
            return Optional.of(new DrawerParcelData(
                    UUID.fromString(parcelId),
                    UUID.fromString(ownerId),
                    template,
                    count
            ));
        } catch (final RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public boolean isParcel(final ItemStack item) {
        return read(item).isPresent();
    }

    public ClaimResult claim(final Player player, final ItemStack parcel) {
        final DrawerParcelData data = read(parcel).orElse(null);
        if (data == null) {
            return ClaimResult.notParcel();
        }
        if (!data.ownerId().equals(player.getUniqueId())) {
            return ClaimResult.ownerOnly(data.ownerId());
        }
        long remaining = data.count();
        long moved = 0L;
        final int nativeStackSize = data.template().getMaxStackSize();
        while (remaining > 0L) {
            final int offer = (int) Math.min((long) nativeStackSize, remaining);
            final ItemStack stack = data.template().clone();
            stack.setAmount(offer);
            final Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
            final int leftover = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
            final int inserted = offer - leftover;
            if (inserted <= 0) {
                break;
            }
            moved += inserted;
            remaining -= inserted;
            if (leftover > 0) {
                break;
            }
        }

        if (moved == 0L) {
            return ClaimResult.inventoryFull(data.ownerId());
        }
        if (remaining == 0L) {
            return ClaimResult.claimed(data.ownerId(), moved, true);
        }
        write(parcel, new DrawerParcelData(data.parcelId(), data.ownerId(), data.template(), remaining));
        return ClaimResult.claimed(data.ownerId(), moved, false);
    }

    /** Drops one normal-aging item entity owned by the breaker. */
    public Item dropForBreak(final Player owner, final Location location, final ItemStack template, final long count) {
        return dropForBreak(owner.getUniqueId(), location, template, count);
    }

    /** UUID overload used by headless acceptance rigs and recovery paths. */
    public Item dropForBreak(final UUID ownerId, final Location location, final ItemStack template, final long count) {
        final ItemStack parcel = createParcel(ownerId, template, count);
        final Item item = location.getWorld().dropItem(location, parcel);
        item.setOwner(ownerId);
        configureDroppedParcel(item);
        return item;
    }

    /** Keeps every world parcel conspicuous, including parcels re-dropped by a player. */
    public void configureDroppedParcel(final Item item) {
        final DrawerParcelData data = read(item.getItemStack()).orElse(null);
        if (data == null) {
            return;
        }
        item.customName(parcelName(data.template(), data.count()));
        item.setCustomNameVisible(true);
        item.setGlowing(true);
    }

    private void write(final ItemStack item, final DrawerParcelData data) {
        final ItemMeta meta = item.getItemMeta();
        if (meta instanceof BundleMeta bundleMeta) {
            // The drawer count can exceed vanilla bundle capacity. PDC remains the sole source of
            // truth, and the native bundle storage stays empty so it cannot split or duplicate it.
            bundleMeta.setItems(List.of());
        }
        meta.displayName(parcelName(data.template(), data.count()));
        meta.lore(List.of(
                Component.text("Walk over it to claim contents", NamedTextColor.GRAY)
                        .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE),
                Component.text("Owner: " + data.ownerId(), NamedTextColor.DARK_GRAY)
                        .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
        ));
        final PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(MARKER, PersistentDataType.BYTE, (byte) 1);
        pdc.set(PARCEL_ID, PersistentDataType.STRING, data.parcelId().toString());
        pdc.set(OWNER_ID, PersistentDataType.STRING, data.ownerId().toString());
        pdc.set(TEMPLATE, PersistentDataType.BYTE_ARRAY, data.template().serializeAsBytes());
        pdc.set(COUNT, PersistentDataType.LONG, data.count());
        item.setItemMeta(meta);
    }

    private static Component parcelName(final ItemStack template, final long count) {
        final Component itemName = template.getItemMeta().hasDisplayName()
                ? template.displayName()
                : Component.translatable(template);
        return itemName
                .append(Component.text(" ×" + count, NamedTextColor.GOLD))
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public record ClaimResult(ClaimStatus status, UUID ownerId, long moved, boolean exhausted) {
        static ClaimResult notParcel() {
            return new ClaimResult(ClaimStatus.NOT_PARCEL, null, 0L, false);
        }

        static ClaimResult ownerOnly(final UUID ownerId) {
            return new ClaimResult(ClaimStatus.OWNER_ONLY, ownerId, 0L, false);
        }

        static ClaimResult inventoryFull(final UUID ownerId) {
            return new ClaimResult(ClaimStatus.INVENTORY_FULL, ownerId, 0L, false);
        }

        static ClaimResult claimed(final UUID ownerId, final long moved, final boolean exhausted) {
            return new ClaimResult(ClaimStatus.CLAIMED, ownerId, moved, exhausted);
        }
    }

    public enum ClaimStatus {
        NOT_PARCEL,
        OWNER_ONLY,
        INVENTORY_FULL,
        CLAIMED
    }
}
