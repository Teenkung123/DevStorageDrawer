package com.Teenkung.devStorageDrawer.bedrock;

import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;

/** Identifies Bedrock players without packet interception. */
public interface BedrockPlayerDetector {
    BedrockPlayerDetector NONE = new BedrockPlayerDetector() {
        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public boolean isBedrock(final Player player) {
            return false;
        }
    };

    static BedrockPlayerDetector create(final PluginManager pluginManager) {
        final boolean floodgate = pluginManager.isPluginEnabled("floodgate");
        final boolean geyser = pluginManager.isPluginEnabled("Geyser-Spigot")
                || pluginManager.isPluginEnabled("Geyser");
        if (!floodgate && !geyser) {
            return NONE;
        }
        return new GeyserFloodgatePlayerDetector(floodgate, geyser);
    }

    boolean isAvailable();

    boolean isBedrock(Player player);
}
