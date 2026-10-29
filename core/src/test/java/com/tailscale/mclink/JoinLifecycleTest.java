package com.tailscale.mclink;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JoinLifecycleTest {
    @Test void ignoresOnlyTheInitialReentrantDisconnect() {
        JoinLifecycle lifecycle = new JoinLifecycle();
        assertFalse(lifecycle.shouldDisconnect());
        lifecycle.beginConnect(() -> assertFalse(lifecycle.shouldDisconnect(),
                "startConnecting clears the old world and posts LoggingOut synchronously"));
        assertTrue(lifecycle.shouldDisconnect(), "a login failure immediately after startConnecting must close the tunnel");
    }
    @Test void failedTransitionDoesNotLeaveDisconnectSuppressed() {
        JoinLifecycle lifecycle = new JoinLifecycle();
        IllegalStateException failure = new IllegalStateException("connect failed");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> lifecycle.beginConnect(() -> {
            assertFalse(lifecycle.shouldDisconnect());
            throw failure;
        })));
        assertTrue(lifecycle.shouldDisconnect());
    }
    @Test void nestedTransitionsRemainSuppressedUntilOuterReturn() {
        JoinLifecycle lifecycle = new JoinLifecycle();
        lifecycle.beginConnect(() -> {
            lifecycle.beginConnect(() -> assertFalse(lifecycle.shouldDisconnect()));
            assertFalse(lifecycle.shouldDisconnect());
        });
        assertTrue(lifecycle.shouldDisconnect());
    }
}
