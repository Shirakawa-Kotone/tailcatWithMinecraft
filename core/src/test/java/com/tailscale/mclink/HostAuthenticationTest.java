package com.tailscale.mclink;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HostAuthenticationTest {
    @Test void restoringInsideTheServerTaskMustNotQueueBehindANewerPolicy() {
        AtomicBoolean server = new AtomicBoolean(true);
        HostAuthentication lease = new HostAuthentication(server::get, server::set);
        lease.apply(false);
        lease.restore(task -> fail("A reentrant executor would put restoration behind the replacement policy"), () -> true);
        assertTrue(server.get());
    }
    @Test void restoringFromClientIsQueuedBeforeTheNextConfiguration() {
        AtomicBoolean server = new AtomicBoolean(true);
        HostAuthentication first = new HostAuthentication(server::get, server::set);
        first.apply(false);
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        first.restore(queue::add, () -> false);
        CompletableFuture<HostAuthentication> next = HostAuthentication.configure(queue::add, server::get, server::set, false);
        while (!queue.isEmpty()) queue.remove().run();
        next.join().close();
        assertTrue(server.get());
    }
    @Test void cancelledQueuedConfigurationCannotDisableAuthenticationLater() {
        AtomicBoolean server = new AtomicBoolean(true);
        Runnable[] queued = new Runnable[1];
        CompletableFuture<HostAuthentication> future = HostAuthentication.configure(task -> queued[0] = task, server::get, server::set, false);
        future.cancel(false);
        queued[0].run();
        assertTrue(server.get());
    }
    @Test void cancellationDuringApplyRestoresBeforeTheServerTaskReturns() {
        AtomicBoolean server = new AtomicBoolean(true);
        Runnable[] queued = new Runnable[1];
        @SuppressWarnings("unchecked") CompletableFuture<HostAuthentication>[] pending = new CompletableFuture[1];
        pending[0] = HostAuthentication.configure(task -> queued[0] = task, server::get, value -> {
            server.set(value);
            if (!value) pending[0].cancel(false);
        }, false);
        queued[0].run();
        assertTrue(server.get());
    }
    @Test void defaultsToOfflineAndRestoresTheHostSetting() {
        AtomicBoolean server = new AtomicBoolean(true);
        HostAuthentication lease = new HostAuthentication(server::get, server::set);
        assertFalse(HostAuthentication.DEFAULT_REQUIRED);
        lease.apply(HostAuthentication.DEFAULT_REQUIRED);
        assertFalse(server.get());
        lease.close();
        assertTrue(server.get());
    }
    @Test void optInVerificationRestoresAnOriginallyOfflineWorld() {
        AtomicBoolean server = new AtomicBoolean(false);
        HostAuthentication lease = new HostAuthentication(server::get, server::set);
        lease.apply(true);
        assertTrue(server.get());
        lease.close();
        assertFalse(server.get());
    }
    @Test void stoppingIsIdempotentAndPreventsDelayedActivation() {
        AtomicBoolean server = new AtomicBoolean(true);
        AtomicInteger writes = new AtomicInteger();
        HostAuthentication lease = new HostAuthentication(server::get, value -> { server.set(value); writes.incrementAndGet(); });
        lease.apply(false);
        lease.close();
        lease.close();
        assertEquals(2, writes.get());
        assertThrows(IllegalStateException.class, () -> lease.apply(false));
        assertTrue(server.get());
    }
    @Test void aFailedChangeRollsBackBeforeItEscapes() {
        AtomicBoolean server = new AtomicBoolean(true);
        HostAuthentication lease = new HostAuthentication(server::get, value -> {
            server.set(value);
            if (!value) throw new IllegalStateException("change rejected");
        });
        assertThrows(IllegalStateException.class, () -> lease.apply(false));
        assertTrue(server.get());
    }
    @Test void closingAnUnusedLeaseDoesNotChangeTheWorld() {
        AtomicBoolean server = new AtomicBoolean(true);
        AtomicInteger writes = new AtomicInteger();
        HostAuthentication lease = new HostAuthentication(server::get, value -> writes.incrementAndGet());
        lease.close();
        assertEquals(0, writes.get());
    }
}
