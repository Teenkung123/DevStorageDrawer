# DevStorageDrawer

DevStorageDrawer is a configurable single-item storage-drawer plugin for Paper and Folia 26.1.2. It uses tagged vanilla barrels as the physical storage mirror, keeps the logical item total in persistent data, and supports vanilla hopper automation without requiring NMS, reflection, ProtocolLib, or an NBT API.

## Requirements

- Java 25
- Paper or Folia 26.1.2
- Optional: Geyser/Floodgate for Bedrock player detection and fallback rendering

Builds use the included Gradle wrapper and target the Paper API as a compile-only dependency.

## Features

- Configurable drawer tiers and capacity based on the stored item's native stack size
- Single-item storage with deposits, withdrawals, shift interactions, and inventory-aware transfers
- Vanilla barrel mirror compatible with hopper automation
- Comparator output based on logical capacity
- Java ItemDisplay/TextDisplay rendering with Bedrock fallback entities when Floodgate is available
- Safe filled-drawer break handling with owner-bound contents parcels
- Persistent state recovery, repair, explicit capacity migration, and journaled transactions
- Folia-aware scheduling and a public API for integrations

Multi-item storage, crafting recipes, locking, voiding, controllers, remotes, and personal security are outside the current v1 scope.

## Release

The current release line is **1.0**. Release jars and their SHA-256 checksums
are distributed through the [GitHub Releases page](https://github.com/Teenkung123/DevStorageDrawer/releases).
Verify the checksum before copying a jar to a production server.

## Build and test

```powershell
.\gradlew.bat clean test
.\gradlew.bat build
```

The resulting plugin jar is written to `build/libs/`.

## Installation

1. Build the project with the Gradle wrapper.
2. Copy the jar from `build/libs/` into the server's `plugins/` directory.
3. Start or restart the Paper/Folia server.
4. Configure `plugins/DevStorageDrawer/config.yml`, `tiers.yml`, and `messages.yml` as needed.

The plugin does not add crafting recipes. Administrators issue configured drawer tiers with `/drawers give`.

## Commands and permissions

| Command | Permission | Purpose |
| --- | --- | --- |
| `/drawers give` | `devstoragedrawer.admin.give` | Give a configured drawer tier |
| `/drawers reload` | `devstoragedrawer.admin.reload` | Reload plugin configuration |
| `/drawers repair` | `devstoragedrawer.admin.repair` | Repair display entities and persisted state |
| `/drawers migrate` | `devstoragedrawer.admin.migrate` | Apply an explicit configured-tier capacity migration |

Players using placed drawers require `devstoragedrawer.use`. The parent permission `devstoragedrawer.admin` grants all administration permissions and defaults to server operators.

## Public API

Third-party integrations should use the contracts under `com.teenkung.devstoragedrawer.api`. The API is published through Bukkit's `ServicesManager` and exposes immutable snapshots, query operations, transfers, and drawer transaction events. See [API.md](API.md) for the integration contract and transaction ownership rules.

## Configuration

- `config.yml` controls general behavior, automation, rendering, Bedrock handling, and diagnostics.
- `tiers.yml` defines the admin-issued drawer tiers and their stack capacities.
- `messages.yml` contains user-facing messages.

Existing drawers retain their capacity snapshot. Use the explicit migration command when a configured tier's capacity must be changed safely.

## License

No license has been selected for this project yet. Until one is added, all rights are reserved.
