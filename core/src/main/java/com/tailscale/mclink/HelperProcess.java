package com.tailscale.mclink;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Owns one helper; callbacks run on its reader thread and never under process locks. */
public final class HelperProcess implements AutoCloseable {
    private static final ScheduledThreadPoolExecutor TIMEOUTS = new ScheduledThreadPoolExecutor(1, action -> {
        Thread thread = new Thread(action, "mclink-helper-timeout");
        thread.setDaemon(true);
        return thread;
    });
    static { TIMEOUTS.setRemoveOnCancelPolicy(true); }
    private final Process process;
    private final Consumer<HelperEvent> events;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final CompletableFuture<HelperEvent> readiness = new CompletableFuture<>();
    private final Object stdinLock = new Object();
    HelperProcess(Process process, Consumer<HelperEvent> events) {
        this.process = Objects.requireNonNull(process);
        this.events = Objects.requireNonNull(events);
        daemon("mclink-helper-stdout", this::readStdout);
        daemon("mclink-helper-stderr", this::drainStderr);
    }
    public static HelperProcess start(List<String> arguments, Path gameDirectory, String modVersion,
            boolean development, Consumer<HelperEvent> events) throws IOException {
        Objects.requireNonNull(events);
        Path executable = NativeHelper.extract(gameDirectory, modVersion, development);
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(arguments);
        return new HelperProcess(new ProcessBuilder(command).start(), events);
    }
    public CompletableFuture<HelperEvent> ready(Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Positive startup timeout required");
        CompletableFuture<HelperEvent> result = new CompletableFuture<>();
        readiness.whenComplete((value, error) -> {
            if (error == null) result.complete(value);
            else result.completeExceptionally(error);
        });
        ScheduledFuture<?> deadline = TIMEOUTS.schedule(
                () -> result.completeExceptionally(new TimeoutException("Helper startup timed out")),
                timeout.toMillis(), TimeUnit.MILLISECONDS);
        result.whenComplete((value, error) -> {
            deadline.cancel(false);
            if (result.isCancelled()) close();
            else if (error instanceof java.util.concurrent.TimeoutException) fail("startup_timeout", "Helper startup timed out");
        });
        return result;
    }
    public boolean isAlive() { return !closing.get() && process.isAlive(); }
    /** True only after the underlying OS process has exited, including during async close. */
    public boolean hasExited() { return !process.isAlive(); }
    public void speedtest() throws IOException {
        synchronized (stdinLock) {
            if (closing.get() || !process.isAlive()) throw new IOException("Helper session is closed");
            process.getOutputStream().write("{\"type\":\"speedtest\"}\n".getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
        }
    }
    private void readStdout() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            while (!closing.get()) {
                String line = readBoundedLine(reader);
                if (line == null) { fail("unexpected_eof", "Helper output closed unexpectedly"); return; }
                HelperEvent event = HelperEvent.parse(line);
                if (closing.get()) return;
                if ("ready".equals(event.type())) {
                    if (!readiness.complete(event)) throw new IOException("Duplicate helper readiness");
                } else if ("error".equals(event.type())) {
                    fail("helper_error", "Helper reported an error");
                    return;
                } else if ("stopped".equals(event.type())) {
                    deliver(event);
                    close();
                    return;
                } else if (!readiness.isDone()) throw new IOException("Helper event before readiness");
                deliver(event);
            }
        } catch (IOException | RuntimeException error) {
            fail("invalid_ipc", "Helper communication failed or contained invalid data");
        }
    }
    private static String readBoundedLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            if (c == '\n') return line.toString();
            if (line.length() >= HelperEvent.MAX_LINE_LENGTH) throw new IOException("Helper event exceeds limit");
            line.append((char)c);
        }
        if (line.length() > 0) throw new IOException("Incomplete helper event");
        return null;
    }
    private void drainStderr() {
        // Stderr can contain invitations or key material. Drain it without persisting raw diagnostics.
        try (InputStream stream = process.getErrorStream()) {
            byte[] buffer = new byte[4096];
            while (stream.read(buffer) != -1) { /* discard */ }
        } catch (IOException ignored) { /* closing the process closes this stream */ }
    }
    private void deliver(HelperEvent event) {
        if (!closing.get()) events.accept(event);
    }
    private void fail(String code, String message) {
        if (closing.get() || !failed.compareAndSet(false, true)) return;
        readiness.completeExceptionally(new IOException(message));
        try { events.accept(new HelperEvent("error", null, null, null, code, message)); }
        catch (RuntimeException ignored) { /* consumer failure must not leak the helper */ }
        finally { close(); }
    }
    @Override public void close() {
        if (!closing.compareAndSet(false, true)) return;
        readiness.completeExceptionally(new IOException("Helper session closed"));
        // A concurrent pipe write must not delay the bounded termination worker.
        daemon("mclink-helper-stdin-close", () -> {
            try { process.getOutputStream().close(); } catch (IOException ignored) {}
        });
        daemon("mclink-helper-close", () -> {
            try {
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroy();
                    if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly();
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
            finally {
                try { process.getInputStream().close(); } catch (IOException ignored) {}
                try { process.getErrorStream().close(); } catch (IOException ignored) {}
            }
        });
    }
    private static void daemon(String name, Runnable action) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        thread.start();
    }
}
