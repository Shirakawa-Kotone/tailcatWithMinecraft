package com.tailscale.mclink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShareViewTest {
    @Test void readinessAndNetworkBelongToTheCurrentHost() {
        ShareView.State state = new ShareView.State();
        Object oldHost = new Object(), currentHost = new Object();
        state.starting(oldHost);
        state.starting(currentHost);
        state.ready(oldHost, "obsolete synthetic invitation");
        assertEquals(ShareView.Phase.STARTING, state.snapshot().phase());
        assertNull(state.snapshot().invitation());
        NetworkStatus status = NetworkStatus.waiting("host");
        state.ready(currentHost, "current synthetic invitation");
        state.network(currentHost, status);
        state.network(oldHost, NetworkStatus.idle());
        assertEquals(ShareView.Phase.ON, state.snapshot().phase());
        assertEquals("current synthetic invitation", state.snapshot().invitation());
        assertSame(status, state.snapshot().network());
    }

    @Test void stoppingCannotBeRevivedByLateReadiness() {
        ShareView.State state = new ShareView.State();
        Object host = new Object();
        state.starting(host);
        state.stopping(host);
        state.ready(host, "late synthetic invitation");
        assertEquals(ShareView.Phase.STOPPING, state.snapshot().phase());
        assertNull(state.snapshot().invitation());
        state.stopped();
        assertEquals(ShareView.Phase.OFF, state.snapshot().phase());
    }

    @Test void oldTerminationCannotTurnOffANewHost() {
        ShareView.State state = new ShareView.State();
        Object old = new Object(), current = new Object();
        state.starting(old);
        state.stopping(old);
        state.starting(current);
        state.stopped();
        state.stopping(old);
        assertEquals(ShareView.Phase.STARTING, state.snapshot().phase());
    }

    @Test void failureSurvivesTerminationUntilExplicitNewShare() {
        ShareView.State state = new ShareView.State();
        Object host = new Object();
        state.starting(host);
        state.failed(host);
        state.stopping(host);
        state.stopped();
        assertEquals(ShareView.Phase.FAILED, state.snapshot().phase());
        assertNotNull(state.snapshot().failure());
        assertNull(state.snapshot().invitation());
        state.starting(new Object());
        assertEquals(ShareView.Phase.STARTING, state.snapshot().phase());
        assertNull(state.snapshot().failure());
    }

    @Test void immediatePublishFailureIsVisibleAndSafe() {
        ShareView.State state = new ShareView.State();
        state.failedToStart();
        assertEquals(ShareView.Phase.FAILED, state.snapshot().phase());
        assertEquals("Sharing failed", state.snapshot().failure());
        assertNotNull(state.snapshot().network());
    }

    @Test void playerSamplesAreImmutableAndWorldBound() {
        ShareView.State state = new ShareView.State();
        Object first = new Object(), second = new Object();
        state.world(first);
        List<String> players = new ArrayList<>(Arrays.asList("Host", "Visitor"));
        state.players(first, players);
        ShareView before = state.snapshot();
        players.clear();
        assertEquals(Arrays.asList("Host", "Visitor"), before.players());
        assertThrows(UnsupportedOperationException.class, () -> before.players().clear());
        state.world(second);
        state.players(first, Arrays.asList("Old visitor"));
        assertFalse(state.snapshot().playersKnown());
        assertTrue(state.snapshot().players().isEmpty());
        state.players(second, Collections.emptyList());
        assertTrue(state.snapshot().playersKnown());
        assertTrue(state.snapshot().players().isEmpty());
        assertEquals(Arrays.asList("Host", "Visitor"), before.players());
        state.world(null);
        assertFalse(state.snapshot().playersKnown());
    }

    @Test void openingAnIdleViewHasNoHostingSideEffects() {
        ShareView.State state = new ShareView.State();
        assertEquals(ShareView.Phase.OFF, state.snapshot().phase());
        assertNull(state.snapshot().invitation());
        assertNull(state.snapshot().failure());
        assertNotNull(state.snapshot().network());
        assertFalse(state.snapshot().playersKnown());
    }

    @Test void familyLoadedByChildLoaderCanUseCanonicalCoreState() throws Exception {
        final String fixtureName = FamilyFixture.class.getName();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream source = FamilyFixture.class.getResourceAsStream("ShareViewTest$FamilyFixture.class")) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = source.read(buffer)) != -1) bytes.write(buffer, 0, count);
        }
        final byte[] bytecode = bytes.toByteArray();
        ClassLoader child = new ClassLoader(ShareView.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!fixtureName.equals(name)) return super.loadClass(name, resolve);
                Class<?> type = findLoadedClass(name);
                if (type == null) type = defineClass(name, bytecode, 0, bytecode.length);
                if (resolve) resolveClass(type);
                return type;
            }
        };
        Class<?> family = Class.forName(fixtureName, true, child);
        assertNotSame(FamilyFixture.class, family);
        ShareView view = (ShareView) family.getMethod("exercise").invoke(null);
        assertEquals(ShareView.Phase.ON, view.phase());
        assertEquals(Arrays.asList("Host"), view.players());
    }

    /** Represents the family code: same package name, separate runtime class loader. */
    public static final class FamilyFixture {
        public static ShareView exercise() {
            ShareView.State state = new ShareView.State();
            Object host = new Object();
            state.world(host);
            state.starting(host);
            state.ready(host, "synthetic invitation");
            state.network(host, NetworkStatus.waiting("host"));
            state.stopping(host);
            state.stopped();
            state.starting(host);
            state.failed(host);
            state.failedToStart();
            state.starting(host);
            state.ready(host, "synthetic invitation");
            state.players(host, Arrays.asList("Host"));
            return state.snapshot();
        }
    }
}
