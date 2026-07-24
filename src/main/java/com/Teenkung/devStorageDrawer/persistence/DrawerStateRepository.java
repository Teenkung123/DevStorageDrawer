package com.Teenkung.devStorageDrawer.persistence;

import com.Teenkung.devStorageDrawer.domain.DrawerState;
import java.util.Objects;
import org.bukkit.block.Barrel;

/** Barrel-specific persistence boundary; it intentionally contains no NMS or reflection. */
public final class DrawerStateRepository {

    private final DrawerPdcCodec codec;

    public DrawerStateRepository() {
        this(new DrawerPdcCodec());
    }

    DrawerStateRepository(final DrawerPdcCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public boolean isDrawer(final Barrel barrel) {
        return this.codec.isDrawer(Objects.requireNonNull(barrel, "barrel").getPersistentDataContainer());
    }

    public DrawerStateReadResult read(final Barrel barrel) {
        return this.codec.read(Objects.requireNonNull(barrel, "barrel").getPersistentDataContainer());
    }

    /** Writes into this barrel state snapshot. Call {@link #save(Barrel, DrawerState)} to persist it immediately. */
    public void write(final Barrel barrel, final DrawerState state) {
        this.codec.write(Objects.requireNonNull(barrel, "barrel").getPersistentDataContainer(), Objects.requireNonNull(state, "state"));
    }

    /**
     * Saves PDC state through Bukkit's public BlockState API without forcing a stale snapshot over
     * a newer live barrel state. Invoke only on the barrel's owning region thread and handle a
     * {@code false} result by retrying from a fresh state snapshot.
     */
    public boolean save(final Barrel barrel, final DrawerState state) {
        this.write(barrel, state);
        return barrel.update(false, false);
    }

    public void clear(final Barrel barrel) {
        this.codec.clear(Objects.requireNonNull(barrel, "barrel").getPersistentDataContainer());
    }

    public boolean clearAndSave(final Barrel barrel) {
        this.clear(barrel);
        return barrel.update(false, false);
    }
}
