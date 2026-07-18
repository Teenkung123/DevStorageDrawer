# DevStorageDrawer — Accepted Implementation Plan

## Goal

Build a Paper and Folia 26.1.2 plugin that turns PDC-tagged vanilla barrels
into configurable, single-item storage drawers. The barrel remains the real
container for vanilla automation; player access uses drawer interactions rather
than a barrel inventory GUI.

## Locked Product Decisions

- Target Java 25, Paper/Folia 26.1.2, and public APIs only. Do not use NMS,
  reflection, ProtocolLib, or NBT-API in v1.
- A tier is a custom PDC-tagged barrel item issued only by admin command. Tier
  definitions are configurable; no crafting recipes are included in v1.
- Capacity is `tier stacks × stored item's native max stack size`; the initial
  example tiers are 32, 64, and 128 stacks.
- V1 is single-item storage only. Multi-item, compacting, locking, voiding,
  controllers, remotes, personal security, framing, and redstone upgrades are
  out of scope.
- Java clients use ItemDisplay and TextDisplay entities. Bedrock support uses
  Geyser on either the proxy or backend plus Floodgate on the Paper backend, and
  a client-specific armor-stand item/name fallback because Geyser does not
  support ItemDisplay.
- A virtual-capacity drawer uses a guarded real-barrel mirror: normally one
  input buffer and up to 26 same-item output slots. A completely full drawer
  may occupy the input slot as well so vanilla can emit comparator level 15.
  InventoryMoveItemEvent guards type and capacity, then reconciles normal
  vanilla automation; it does not replace the inventory with a custom NMS
  handler.
- Comparators report logical fullness: 0 when empty, otherwise
  `1 + floor(14 × stored / capacity)`.
- A broken filled drawer produces an empty tier barrel plus one owner-bound
  contents parcel, never a large world-item flood. Parcels cannot enter hoppers,
  hopper minecarts, containers, or another player's inventory. Ground parcels
  use the normal item despawn timer; unclaimed contents are intentionally lost.
- Tagged drawers are protected from explosions by default and cannot be moved
  by pistons.

## Architecture

1. Retarget the Gradle project and plugin descriptor to Paper/Folia 26.1.2,
   add Folia metadata, normalize the Java package, and add JUnit tests.
2. Persist a `DrawerState` on each Barrel PDC: schema version, tier id,
   serialized exemplar item, hidden item count, effective capacity snapshot,
   proxy journal, and display references. The real barrel inventory is the
   bounded exposed part of storage; total stock is hidden count plus accepted
   proxy contents.
3. Implement internal seams: `DrawerStorageStrategy` with
   `SingleItemDrawerStorage`, `DrawerStateRepository`, `DrawerHopperBridge`,
   `DrawerRenderer`, `DrawerComparatorService`, `ParcelService`, and a
   Folia-aware execution facade. The only public third-party surface in v1 is
   the optional `DrawerSellWandBridge`, which lets DevSMP's Amethyst sell axe
   sell a tagged drawer's logical stock through a journaled, region-owned
   withdrawal rather than its physical barrel mirror.
4. Add `config.yml`, `tiers.yml`, and `messages.yml`. Existing drawers retain
   a capacity snapshot; an explicit admin migration is required to apply a
   lower capacity safely.
5. Add `/drawers give`, `/drawers reload`, `/drawers repair`, and explicit
   `/drawers migrate` with separate admin permissions.

## Interaction and Rendering

- Cancel player barrel opening from every face. Interactions occur only on the
  barrel's facing face; side interactions do nothing.
- Left click withdraws one item. Shift-left moves as much as fits into the
  player's inventory without dropping overflow. Right click deposits the held
  stack. A second matching right click inside ten ticks deposits matching
  inventory contents. Empty-hand shift-right shows a no-GUI message.
- Preserve normal block breaking; when BlockBreakEvent succeeds, safely replace
  the drawer state with the tier barrel item and, when needed, its contents
  parcel.
- Render empty/full states and live name/count/capacity on all six barrel
  facings. Tag, protect, repair, and coalesce display updates. Route direct
  display-entity clicks back to the owning drawer.
- Hide Java display entities from Floodgate players and hide Bedrock fallback
  entities from Java players. In Bedrock-required mode, require the backend
  Floodgate API used for player detection; do not require Geyser to be installed
  locally because it may be running on the proxy.

## Automation, Redstone, and Recovery Gates

- Before shipping automation, build a focused mirror spike and validate hopper
  input/output, hopper minecarts, all sides, wrong-item rejection, full/empty
  state, restart recovery, simultaneous hoppers, and adjacent Folia regions.
- Journal plugin-managed proxy rebalances before mutating state. On startup or
  chunk load, finish, roll back, or quarantine incomplete journal entries; do
  not materialize extra items.
- The BlockRedstoneEvent spike proved that comparator events are only a binary
  powered-state hook and cannot replace the cached analogue output. Logical
  comparator mode therefore shapes the real proxy item count so vanilla's
  27-slot fullness calculation emits the required level, then ticks adjacent
  comparators through Paper's public API. This mode requires automation enabled,
  exactly 26 output proxy slots, and tiers of at least 27 stacks.
- If either public-API spike cannot prove conservation and Folia safety, stop
  that feature before release and request a new approved design rather than add
  NMS or an unsafe workaround.

## Verification

- Unit tests: capacity math, exact item identity, PDC codec errors, migrations,
  journal recovery, parcel restrictions, comparator levels, and configuration
  validation.
- Integration tests: at least 10,000 automation transfers per tier, restart
  during traffic, low/full capacity, wrong input, multiple hoppers, and no
  duplication or loss.
- Runtime validation: Paper 26.1.2 and Folia 26.1.2 startup and interaction
  tests; Folia multi-region tests with no ownership violations; real
  Geyser/Floodgate Bedrock interaction and renderer validation.
- Safety validation: a full Tier 3 break produces at most the tier barrel plus
  one protected parcel, and automation cannot move parcels.

## Source and Compatibility Notes

The supplied Storage Drawers source is MIT licensed. Treat it as a behavioral
reference; retain attribution if source code or assets are copied.

## Implementation Status — 2026-07-15

Implemented: Java 25/Paper 26.1.2 build, tier/config/message loading, PDC drawer
state and codecs, single-item transactions, journaled proxy rebalance/recovery,
fsynced idempotent player-withdrawal receipts, front-face interactions, safe
parcel breaks, vanilla logical comparator mirroring, coalesced Java/Bedrock
renderers, Folia scheduler boundaries, protected display entities, and the four
admin subcommands.

Verified locally: Paper 26.1.2 build 74 and Folia 26.1.2 build 8 pass the same
development-only live acceptance suite. It covers 10,000 conservation cycles per
tier, registry codecs and item identity, wrong/full input, vanilla input/output
hoppers, four simultaneous side hoppers, hopper minecart extraction, all six
display facings and both renderer entity sets, logical comparator level 8 through
a real circuit, parcel transport rejection, and the two-item-entity break ceiling.
The final Folia run contains no region ownership or synchronous teleport errors.

An actual two-boot Paper probe persisted a PREPARED journal with 62 proxy items,
restarted the server, and recovered exactly 200 total items with a reconciled
124-item proxy. A required-backend smoke also loaded Geyser-Spigot 2.11.0 and
Floodgate 2.2.5, enabled DevStorageDrawer with `bedrock.required: true`, and
started Geyser's UDP listener. Required mode must also start with only backend
Floodgate present, covering deployments where Geyser runs on a proxy. The
remaining release assertion is manual testing
with real Java and Bedrock clients for presentation and player input feel; the
headless suite validates the entity types, positions, links, and server-side
interaction routes but cannot see a client's rendered pixels.

Final artifact verification: `gradlew clean test jar` succeeds on Java 25;
the JUnit report contains 18 tests with zero failures/errors (seven registry-
dependent cases are assumption-skipped and covered by the live server suite).
`build/libs/DevStorageDrawer-1.0.jar` contains all four bundled YAML resources
and the runtime/receipt/acceptance classes; its SHA-256 is
`FF0D2A839EE09C002D37F1F18ADED0AF85BA6323978A1DC0B55AE01DACB0140D`.

## Completion Pass — 2026-07-15

The remaining work is implemented and verified in this order:

1. Replace the player-withdrawal loss window with a durable withdrawal-receipt
   ledger. A region-owned drawer journal is written first, receipt IO runs on
   the async scheduler using atomic file replacement, and delivery is
   idempotent per operation. No world, block, entity, or inventory access may
   occur on the IO scheduler.
2. Add a development-only live acceptance runner, disabled unless an explicit
   JVM system property is supplied. It creates and removes its own isolated
   rig, exercises registry-backed codecs and item identity, performs at least
   10,000 conservation cycles per configured tier, tests wrong/full/empty
   transfers, interrupted-journal recovery, comparator levels, all six display
   facings, parcel automation guards, and the full-drawer two-drop ceiling.
3. Run the acceptance runner on both Paper 26.1.2 and Folia 26.1.2. Run
   additional backend smokes with Geyser plus Floodgate and with Floodgate only.
   Real Java and Bedrock client presentation remains a manual visual assertion; the automated
   gate verifies the exact entity types, ownership links, transforms, and
   per-client renderer selection that the clients receive.
4. Keep acceptance-only hooks inaccessible to players and absent from normal
   command/permission surfaces. A failed gate logs a structured failure, cleans
   the isolated rig where safe, and stops the acceptance server without silently
   certifying it.
