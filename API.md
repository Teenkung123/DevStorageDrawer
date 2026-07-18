# DevStorageDrawer API

DevStorageDrawer exposes a stable, Folia-safe contract in
`com.teenkung.devstoragedrawer.api`. Add DevStorageDrawer as a compile-only
dependency and declare it as a hard `depend` (or a `softdepend` when the
integration is optional).

```yaml
depend: [DevStorageDrawer]
```

Resolve the API through Bukkit's service registry. `DevStorageDrawer#getApi()`
is also available as a convenience for a hard dependency.

```java
final DevStorageDrawerApi api = Bukkit.getServicesManager()
        .load(DevStorageDrawerApi.class);
if (api == null) {
    return;
}

final DrawerLocation drawer = DrawerLocation.from(block.getLocation());
api.query(drawer).thenAccept(result -> {
    if (result.status() == DrawerQueryStatus.FOUND) {
        final DrawerSnapshot snapshot = result.snapshot().orElseThrow();
        // Use the immutable logical view; do not access the barrel inventory directly.
    }
});
```

## Folia contract

All location-based operations return `CompletableFuture`s and schedule their
drawer work on the owning region. Never call `join()` or `get()` from a Paper,
Folia, entity, or region scheduler thread. Compose the future instead. API work
and events run on the appropriate drawer or global scheduler, but a continuation
registered after a future has already completed runs on the registering thread.
Always schedule before touching Bukkit state in a continuation.

`DrawerLocation` is the only location handle retained by the API. Do not cache a
`Block`, `Barrel`, or `Location` across an asynchronous callback.

## Transfers

The API always copies `ItemStack` values and never changes the stack or inventory
provided by the calling plugin. It is not a distributed transaction coordinator:
before invoking `deposit` or `withdraw`, create a durable operation/escrow record
in your own plugin. If a crash leaves the outcome unknown, retain that escrow and
resolve it through your own business-level or manual reconciliation; do not
blindly retry or release it. A future API version may add drawer-side operation
deduplication for exactly-once economy or shop workflows.

```java
final DrawerOperationContext context = DrawerOperationContext.forActor(
        this, "market purchase", player.getUniqueId());

api.deposit(drawer, offeredStack, offeredStack.getAmount(), context)
        .thenAccept(result -> {
            if (result.status() == DrawerTransferStatus.APPLIED) {
                // Mark your pre-existing escrow operation complete now.
            }
        });
```

`withdraw` returns exact stack-sized copies in
`DrawerTransferResult.withdrawnItems()`. The drawer debit is journaled and durable
before the future completes. Two plugins cannot share an atomic database commit,
so a caller that cannot consume withdrawn items must compensate with a deposit.
Use `DrawerTransferStatus` rather than inferring a result from a count: it
distinguishes full, empty, mismatched, recovering, corrupt, scheduler-unavailable,
and persistence-failure outcomes.

`reconcile` intentionally runs recovery for a drawer with a pending automation
journal before returning its current query result.

## Events

Subscribe through normal Bukkit listeners:

- `DrawerPreTransactionEvent` is cancellable and fires only for API deposits and withdrawals.
- `DrawerTransactionCompletedEvent` fires after a successful API transfer commits.
- `DrawerContentsChangedEvent` fires after every persisted drawer change, including players,
  automation, recovery, migration, external sales, and API operations.

All events are synchronous on the drawer's owning Folia region. Event payloads are
immutable snapshots or defensive item copies; they intentionally do not expose
PDC state, the real barrel mirror, storage strategies, or recovery journals.

## Tiers and compatibility

`tiers()` returns a status-bearing `DrawerTierQueryResult` with immutable
configured metadata, and `createTierItem(id, amount)` creates tagged tier barrel
items without command permissions.
`DevStorageDrawerApi.VERSION` is the semantic API version. Integrations should
compile against the API package only and must not use `block`, `domain`, `hopper`,
or `persistence` implementation classes. The older sell-wand bridge remains for
binary compatibility but is deprecated because it requires manual Folia ownership.
