package com.teenkung.devstoragedrawer.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/** Immutable message catalogue. Placeholder values are intentionally escaped as plain text. */
public final class DrawerMessages {
    private final Map<String, String> rawMessages;
    private final MiniMessage miniMessage;

    public DrawerMessages(final Map<String, String> rawMessages) {
        this.rawMessages = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(rawMessages, "rawMessages")));
        this.miniMessage = MiniMessage.miniMessage();
    }

    public Component component(final String key) {
        return component(key, Map.of());
    }

    public Component component(final String key, final Map<String, ?> placeholders) {
        final String prefix = rawMessages.getOrDefault("prefix", "");
        String raw = prefix + rawMessages.getOrDefault(key, "<red>Missing message: " + key);
        for (final Map.Entry<String, ?> entry : placeholders.entrySet()) {
            raw = raw.replace("<" + entry.getKey() + ">", MiniMessage.miniMessage().serialize(
                    Component.text(String.valueOf(entry.getValue()))
            ));
        }
        return miniMessage.deserialize(raw);
    }
}
