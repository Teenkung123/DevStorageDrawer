package com.Teenkung.devStorageDrawer.config;

import com.Teenkung.devStorageDrawer.domain.DrawerTier;
import com.Teenkung.devStorageDrawer.persistence.DrawerPdcKeys;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Configured presentation and runtime definition of one custom barrel item. */
public record DrawerTierDefinition(
        DrawerTier tier,
        Component itemName,
        List<Component> itemLore,
        Integer customModelData
) {
    public DrawerTierDefinition {
        Objects.requireNonNull(tier, "tier");
        itemName = Objects.requireNonNull(itemName, "itemName")
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
        itemLore = Objects.requireNonNull(itemLore, "itemLore").stream()
                .map(line -> line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE))
                .toList();
        if (customModelData != null && customModelData < 0) {
            throw new IllegalArgumentException("customModelData must not be negative");
        }
    }

    public ItemStack createItem() {
        final ItemStack item = new ItemStack(Material.BARREL);
        final ItemMeta meta = item.getItemMeta();
        meta.displayName(itemName);
        meta.lore(itemLore);
        if (customModelData != null) {
            meta.setCustomModelData(customModelData);
        }
        meta.getPersistentDataContainer().set(DrawerPdcKeys.TIER_ID, PersistentDataType.STRING, tier.id());
        item.setItemMeta(meta);
        return item;
    }
}
