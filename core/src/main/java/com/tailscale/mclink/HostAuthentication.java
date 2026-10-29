package com.tailscale.mclink;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** A host's temporary authentication policy, restored when sharing ends. */
public final class HostAuthentication implements AutoCloseable {
    public static final boolean DEFAULT_REQUIRED = false;
    private final Consumer<Boolean> update;
    private final boolean original;
    private boolean applied;
    private boolean closed;

    public HostAuthentication(BooleanSupplier current, Consumer<Boolean> update) {
        this.original = Objects.requireNonNull(current).getAsBoolean();
        this.update = Objects.requireNonNull(update);
    }
    /** Configure on the server executor; cancellation must never leave a late policy behind. */
    public static CompletableFuture<HostAuthentication> configure(Executor server, BooleanSupplier current,
            Consumer<Boolean> update, boolean required) {
        CompletableFuture<HostAuthentication> result = new CompletableFuture<>();
        try {
            server.execute(() -> {
                if (result.isCancelled()) return;
                try {
                    HostAuthentication lease = new HostAuthentication(current, update);
                    lease.apply(required);
                    if (!result.complete(lease)) lease.close();
                } catch (RuntimeException failure) { result.completeExceptionally(failure); }
            });
        } catch (RuntimeException failure) { result.completeExceptionally(failure); }
        return result;
    }
    public synchronized void apply(boolean required) {
        if (closed) throw new IllegalStateException("Host authentication policy is closed");
        applied = true;
        try { update.accept(required); }
        catch (RuntimeException failure) {
            try { close(); } catch (RuntimeException restore) { failure.addSuppressed(restore); }
            throw failure;
        }
    }
    /** A reentrant server executor can defer same-thread execute behind a newer lease. */
    public void restore(Executor server, BooleanSupplier onServerThread) {
        if (onServerThread.getAsBoolean()) close();
        else {
            try { server.execute(this::close); }
            catch (RuntimeException shuttingDown) { close(); }
        }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (applied) update.accept(original);
    }
}
