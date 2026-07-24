package com.Teenkung.devStorageDrawer.command;

import com.Teenkung.devStorageDrawer.config.DrawerConfigurationService;
import com.Teenkung.devStorageDrawer.config.DrawerMessages;
import com.Teenkung.devStorageDrawer.config.DrawerTierDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Permission-guarded administration command for configured drawer tiers. */
public final class DrawersCommand implements CommandExecutor, TabCompleter {
    private final DrawerConfigurationService configuration;
    private final DrawerAdminFacade runtime;

    public DrawersCommand(final DrawerConfigurationService configuration, final DrawerAdminFacade runtime) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args
    ) {
        if (args.length == 0) {
            send(sender, "command.usage");
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> give(sender, args);
            case "reload" -> reload(sender);
            case "repair" -> repair(sender);
            case "migrate" -> migrate(sender);
            default -> {
                send(sender, "command.usage");
                yield true;
            }
        };
    }

    private boolean give(final CommandSender sender, final String[] args) {
        if (!sender.hasPermission("devstoragedrawer.admin.give")) {
            send(sender, "general.no-permission");
            return true;
        }
        if (args.length < 3 || args.length > 4) {
            send(sender, "command.give-usage");
            return true;
        }
        final Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            send(sender, "command.player-not-found", Map.of("player", args[1]));
            return true;
        }
        final DrawerTierDefinition tier = configuration.tier(args[2]).orElse(null);
        if (tier == null) {
            send(sender, "command.unknown-tier", Map.of("tier", args[2]));
            return true;
        }
        final int amount = args.length == 4 ? positiveAmount(sender, args[3]) : 1;
        if (amount < 1) {
            return true;
        }
        giveItems(target, tier, amount);
        send(sender, "command.given", Map.of(
                "amount", amount,
                "tier", tier.tier().id(),
                "player", target.getName()
        ));
        return true;
    }

    private int positiveAmount(final CommandSender sender, final String source) {
        try {
            final int parsed = Integer.parseInt(source);
            if (parsed < 1) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (final NumberFormatException exception) {
            send(sender, "command.invalid-amount");
            return -1;
        }
    }

    private static void giveItems(final Player target, final DrawerTierDefinition tier, final int amount) {
        int remaining = amount;
        while (remaining > 0) {
            final ItemStack stack = tier.createItem();
            final int batch = Math.min(remaining, stack.getMaxStackSize());
            stack.setAmount(batch);
            final Map<Integer, ItemStack> leftovers = target.getInventory().addItem(stack);
            for (final ItemStack leftover : leftovers.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), leftover);
            }
            remaining -= batch;
        }
    }

    private boolean reload(final CommandSender sender) {
        if (!sender.hasPermission("devstoragedrawer.admin.reload")) {
            send(sender, "general.no-permission");
            return true;
        }
        try {
            configuration.load();
            runtime.reload(configuration.settings(), configuration.messages());
            send(sender, "command.reloaded");
        } catch (final IllegalArgumentException exception) {
            sender.sendMessage(configuration.messages().component("general.configuration-error"));
        }
        return true;
    }

    private boolean repair(final CommandSender sender) {
        if (!sender.hasPermission("devstoragedrawer.admin.repair")) {
            send(sender, "general.no-permission");
            return true;
        }
        runtime.repairLoadedDrawers();
        send(sender, "command.repair-queued");
        return true;
    }

    private boolean migrate(final CommandSender sender) {
        if (!sender.hasPermission("devstoragedrawer.admin.migrate")) {
            send(sender, "general.no-permission");
            return true;
        }
        if (!(sender instanceof Player player)) {
            send(sender, "command.player-only");
            return true;
        }
        runtime.migrateTargetedDrawer(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String alias,
            final String[] args
    ) {
        if (args.length == 1) {
            return matching(args[0], List.of("give", "reload", "repair", "migrate"));
        }
        if (args.length == 2 && "give".equalsIgnoreCase(args[0])) {
            return matching(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        if (args.length == 3 && "give".equalsIgnoreCase(args[0])) {
            return matching(args[2], new ArrayList<>(configuration.settings().tiers().keySet()));
        }
        return List.of();
    }

    private static List<String> matching(final String prefix, final List<String> candidates) {
        final String normalized = prefix.toLowerCase(Locale.ROOT);
        return candidates.stream()
                .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(normalized))
                .sorted()
                .collect(Collectors.toList());
    }

    private void send(final CommandSender sender, final String key) {
        sender.sendMessage(configuration.messages().component(key));
    }

    private void send(final CommandSender sender, final String key, final Map<String, ?> placeholders) {
        sender.sendMessage(configuration.messages().component(key, placeholders));
    }
}
