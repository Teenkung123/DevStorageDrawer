package com.teenkung.devstoragedrawer.domain;

import java.util.Objects;
import java.util.Optional;
import org.bukkit.inventory.ItemStack;

/**
 * A fully calculated, but not yet applied, drawer transfer. The runtime must apply the returned
 * state and physical proxy delta together on the barrel's owning Folia region thread.
 */
public final class DrawerStorageTransaction {

    private final DrawerOperation operation;
    private final DrawerTransactionStatus status;
    private final DrawerState stateBefore;
    private final DrawerState stateAfter;
    private final long physicalBefore;
    private final long physicalAfter;
    private final long requestedAmount;
    private final long acceptedAmount;
    private final ItemStack itemTemplate;

    private DrawerStorageTransaction(
            final DrawerOperation operation,
            final DrawerTransactionStatus status,
            final DrawerState stateBefore,
            final DrawerState stateAfter,
            final long physicalBefore,
            final long physicalAfter,
            final long requestedAmount,
            final long acceptedAmount,
            final ItemStack itemTemplate
    ) {
        this.operation = Objects.requireNonNull(operation, "operation");
        this.status = Objects.requireNonNull(status, "status");
        this.stateBefore = Objects.requireNonNull(stateBefore, "stateBefore");
        this.stateAfter = Objects.requireNonNull(stateAfter, "stateAfter");
        DrawerCapacity.requireNonNegative(physicalBefore, "transaction physical-before count");
        DrawerCapacity.requireNonNegative(physicalAfter, "transaction physical-after count");
        DrawerCapacity.requireNonNegative(requestedAmount, "transaction requested amount");
        DrawerCapacity.requireNonNegative(acceptedAmount, "transaction accepted amount");
        if (acceptedAmount > requestedAmount) {
            throw new DrawerInvariantViolationException("A drawer transaction cannot accept more than was requested");
        }
        if (acceptedAmount > 0L) {
            DrawerItemIdentity.requireStorageCandidate(itemTemplate, "Transaction item template");
        }

        final long totalBefore = stateBefore.totalForPhysical(physicalBefore);
        final long totalAfter = stateAfter.totalForPhysical(physicalAfter);
        final long expectedTotalAfter = switch (operation) {
            case PLAYER_DEPOSIT, PROXY_INSERT -> DrawerCapacity.checkedAdd(
                    totalBefore,
                    acceptedAmount,
                    "Drawer transfer total"
            );
            case PLAYER_WITHDRAW, PROXY_EXTRACT -> DrawerCapacity.checkedSubtract(
                    totalBefore,
                    acceptedAmount,
                    "Drawer transfer total"
            );
        };
        if (totalAfter != expectedTotalAfter) {
            throw new DrawerInvariantViolationException(
                    "Drawer transaction does not conserve items: expected " + expectedTotalAfter + " but got " + totalAfter
            );
        }

        this.physicalBefore = physicalBefore;
        this.physicalAfter = physicalAfter;
        this.requestedAmount = requestedAmount;
        this.acceptedAmount = acceptedAmount;
        this.itemTemplate = itemTemplate == null ? null : DrawerItemIdentity.templateOf(itemTemplate);
    }

    static DrawerStorageTransaction applied(
            final DrawerOperation operation,
            final DrawerState stateBefore,
            final DrawerState stateAfter,
            final long physicalBefore,
            final long physicalAfter,
            final long requestedAmount,
            final long acceptedAmount,
            final ItemStack itemTemplate
    ) {
        return new DrawerStorageTransaction(
                operation,
                DrawerTransactionStatus.APPLIED,
                stateBefore,
                stateAfter,
                physicalBefore,
                physicalAfter,
                requestedAmount,
                acceptedAmount,
                itemTemplate
        );
    }

    static DrawerStorageTransaction rejected(
            final DrawerOperation operation,
            final DrawerTransactionStatus status,
            final DrawerState state,
            final long physicalCount,
            final long requestedAmount
    ) {
        if (status == DrawerTransactionStatus.APPLIED) {
            throw new IllegalArgumentException("Use applied() for an accepted transaction");
        }
        return new DrawerStorageTransaction(
                operation,
                status,
                state,
                state,
                physicalCount,
                physicalCount,
                requestedAmount,
                0L,
                null
        );
    }

    public DrawerOperation operation() {
        return this.operation;
    }

    public DrawerTransactionStatus status() {
        return this.status;
    }

    public boolean accepted() {
        return this.acceptedAmount > 0L;
    }

    public DrawerState stateBefore() {
        return this.stateBefore;
    }

    public DrawerState stateAfter() {
        return this.stateAfter;
    }

    public long physicalBefore() {
        return this.physicalBefore;
    }

    public long physicalAfter() {
        return this.physicalAfter;
    }

    public long requestedAmount() {
        return this.requestedAmount;
    }

    public long acceptedAmount() {
        return this.acceptedAmount;
    }

    public long rejectedAmount() {
        return this.requestedAmount - this.acceptedAmount;
    }

    /** A defensive amount-one copy of the item transferred by this transaction. */
    public Optional<ItemStack> itemTemplate() {
        return this.itemTemplate == null ? Optional.empty() : Optional.of(this.itemTemplate.clone());
    }

    /**
     * Creates one output stack of the requested size. For large logical transfers callers must
     * split the transfer into multiple Bukkit stacks themselves.
     */
    public ItemStack createTransferredStack(final int amount) {
        if (!accepted()) {
            throw new DrawerInvariantViolationException("A rejected drawer transaction has no transferred item");
        }
        if (amount <= 0 || (long) amount > this.acceptedAmount) {
            throw new DrawerValidationException("Requested output stack amount is outside the accepted transfer");
        }
        final ItemStack stack = this.itemTemplate.clone();
        stack.setAmount(amount);
        return stack;
    }
}
