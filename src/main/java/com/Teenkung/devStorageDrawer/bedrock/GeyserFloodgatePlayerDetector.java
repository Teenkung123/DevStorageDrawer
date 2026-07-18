package com.teenkung.devstoragedrawer.bedrock;

import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.geyser.api.GeyserApi;

/**
 * Direct, optional bridge integration. This class is constructed only when one
 * of the corresponding plugins is enabled, so Java-only deployments do not
 * invoke a missing compile-only API.
 */
final class GeyserFloodgatePlayerDetector implements BedrockPlayerDetector {
    private final boolean floodgate;
    private final boolean geyser;

    GeyserFloodgatePlayerDetector(final boolean floodgate, final boolean geyser) {
        this.floodgate = floodgate;
        this.geyser = geyser;
    }

    @Override
    public boolean isAvailable() {
        return floodgate || geyser;
    }

    @Override
    public boolean isBedrock(final Player player) {
        if (floodgate) {
            final FloodgateApi floodgateApi = FloodgateApi.getInstance();
            if (floodgateApi != null && floodgateApi.isFloodgatePlayer(player.getUniqueId())) {
                return true;
            }
        }
        if (!geyser) {
            return false;
        }
        final GeyserApi geyserApi = GeyserApi.api();
        return geyserApi != null && geyserApi.connectionByUuid(player.getUniqueId()) != null;
    }
}
