package com.tailscale.mclink;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.util.HttpUtil;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** A generation identifies a tunnel, including its asynchronous startup and connect callbacks. */
public final class ScreenState implements AutoCloseable {
    private final Minecraft client = Minecraft.getInstance();
    private final String modVersion;
    private final boolean development;
    private long generation;
    private Session session;
    private boolean authenticationEnabled = HostAuthentication.DEFAULT_REQUIRED;
    ScreenState(String modVersion, boolean development) { this.modVersion = modVersion; this.development = development; }

    public boolean authenticationEnabled() { return authenticationEnabled; }
    public CompletableFuture<String> share() { return share(authenticationEnabled); }
    public CompletableFuture<String> share(boolean requireAuthentication) {
        IntegratedServer server = client.getSingleplayerServer();
        if (server == null) return CompletableFuture.failedFuture(new IllegalStateException("No singleplayer world is open"));
        // Reopening the invitation screen must not kick current visitors.
        if (session != null && session.host == server && session.mode.equals("host") && session.authenticationRequired == requireAuthentication
                && (session.process == null || session.process.isAlive())) return session.invite;
        stop();
        authenticationEnabled = requireAuthentication;
        int port = server.getPort();
        if (!server.isPublished()) {
            port = HttpUtil.getAvailablePort();
            if (!server.publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN, false, port))
                return CompletableFuture.failedFuture(new IllegalStateException("Minecraft could not publish this world"));
        }
        final int forwardPort = port;
        Session expected = new Session(++generation, "host", server);
        session = expected;
        expected.authenticationRequired = requireAuthentication;
        expected.authentication = HostAuthentication.configure(server, server::usesAuthentication,
                server::setUsesAuthentication, requireAuthentication);
        expected.authentication.thenCompose(ignored -> start(expected, List.of("host", "--target", "127.0.0.1:" + forwardPort))).whenComplete((event, error) -> client.execute(() -> {
            if (!current(expected)) return;
            if (error != null) { fail(expected, error); return; }
            expected.invite.complete(event.invite());
        }));
        return expected.invite;
    }

    public CompletableFuture<Void> join(Screen parent, String invitation) {
        stop();
        Session expected = new Session(++generation, "join", null);
        session = expected;
        start(expected, List.of("join", "--invite", invitation.trim())).whenComplete((event, error) -> client.execute(() -> {
            if (!current(expected)) return;
            if (error != null) { fail(expected, error); return; }
            try {
                // This check is on the client thread, immediately before changing the connect screen.
                expected.connecting = true;
                // Forge/NeoForge clear the previous level synchronously here and emit
                // LoggingOut even with no player. Only later disconnects close this join.
                expected.joinLifecycle.beginConnect(() -> GameAdapter.connect(parent, event.address()));
                expected.connected.complete(null);
            } catch (RuntimeException failure) { fail(expected, failure); }
        }));
        return expected.connected;
    }

    private CompletableFuture<HelperEvent> start(Session expected, List<String> arguments) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HelperProcess process = HelperProcess.start(arguments, client.gameDirectory.toPath(), modVersion,
                        development, event -> client.execute(() -> onEvent(expected, event)));
                synchronized (expected) {
                    if (expected.cancelled) { process.close(); throw new CancellationException(); }
                    expected.process = process;
                }
                return process;
            } catch (Exception e) { throw new CompletionException(e); }
        }).thenCompose(process -> process.ready(Duration.ofSeconds(30)));
    }

    private void onEvent(Session expected, HelperEvent event) {
        if (!current(expected)) return;
        if (event.stats() != null) expected.status = event.stats();
        if (event.speedtest() != null && expected.testing) {
            for (String line : event.speedtest().lines()) TailcatClient.message(line);
            if (!event.speedtest().state().equals("running")) expected.testing = false;
        }
        if (event.type().equals("error")) fail(expected, new IllegalStateException(event.message() == null ? "Tunnel failed" : event.message()));
        else if (event.type().equals("stopped")) fail(expected, new IllegalStateException("Tunnel stopped"));
    }

    public NetworkStatus status() { return session == null ? NetworkStatus.idle() : session.status; }
    public void speedtest() {
        Session expected = session;
        if (expected == null || expected.process == null || !expected.process.isAlive()) {
            TailcatClient.message("Tailcat：当前没有可测速的会话。"); return;
        }
        if (expected.testing) { TailcatClient.message("Tailcat：本次测速仍在进行。"); return; }
        expected.testing = true;
        // Sending IPC may involve an OS pipe; never block the render thread.
        CompletableFuture.runAsync(() -> {
            try { expected.process.speedtest(); }
            catch (Exception error) { client.execute(() -> {
                if (current(expected)) { expected.testing = false; TailcatClient.message("Tailcat 测速失败：" + ShareScreen.rootMessage(error)); }
            }); }
        });
    }

    public void tick() {
        Session expected = session;
        if (expected == null) return;
        if (expected.process != null && !expected.process.isAlive()) {
            fail(expected, new IllegalStateException("Tailcat helper exited")); return;
        }
        if (expected.host != null && client.getSingleplayerServer() != expected.host) stop();
        // Do not infer connection cancellation from the active screen: pack download and
        // configuration screens legitimately replace ConnectScreen before entering a world.
        else if (expected.mode.equals("join") && client.level != null) expected.enteredWorld = true;
        else if (expected.enteredWorld && client.level == null) stop();
        else if (expected.connecting && (client.gui.screen() instanceof DisconnectedScreen
                || client.gui.screen() instanceof TitleScreen || client.gui.screen() instanceof JoinMultiplayerScreen)) stop();
    }

    /** Loader network disconnect events cover failures before a world ever exists. */
    public void disconnected() { if (session != null && session.mode.equals("join") && session.joinLifecycle.shouldDisconnect()) stop(); }
    public void cancelJoin() { if (session != null && session.mode.equals("join")) stop(); }
    public void stop() {
        Session stopped = session;
        session = null;
        generation++;
        if (stopped == null) return;
        synchronized (stopped) {
            stopped.cancelled = true;
            if (stopped.process != null) stopped.process.close();
        }
        if (stopped.authentication != null) {
            stopped.authentication.whenComplete((lease, error) -> {
                if (lease == null) return;
                lease.restore(stopped.host, stopped.host::isSameThread);
            });
            stopped.authentication.cancel(false);
        }
        CancellationException cancelled = new CancellationException("Tailcat session closed");
        stopped.invite.completeExceptionally(cancelled);
        stopped.connected.completeExceptionally(cancelled);
    }
    @Override public void close() { stop(); }
    private boolean current(Session expected) { return session == expected && expected.generation == generation && !expected.cancelled; }
    private void fail(Session expected, Throwable error) {
        if (!current(expected)) return;
        expected.invite.completeExceptionally(error);
        expected.connected.completeExceptionally(error);
        TailcatClient.message("Tailcat：" + ShareScreen.rootMessage(error));
        stop();
    }
    private static final class Session {
        final long generation;
        final String mode;
        final IntegratedServer host;
        final JoinLifecycle joinLifecycle = new JoinLifecycle();
        final CompletableFuture<String> invite = new CompletableFuture<>();
        final CompletableFuture<Void> connected = new CompletableFuture<>();
        volatile HelperProcess process;
        volatile boolean cancelled;
        NetworkStatus status;
        boolean connecting, enteredWorld, testing;
        boolean authenticationRequired;
        CompletableFuture<HostAuthentication> authentication;
        Session(long generation, String mode, IntegratedServer host) {
            this.generation = generation; this.mode = mode; this.host = host;
            status = NetworkStatus.waiting(mode);
        }
    }
}
