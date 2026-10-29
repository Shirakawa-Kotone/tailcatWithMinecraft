package com.tailscale.mclink;

import java.io.IOException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HelperProcessTest {
    private Process fixture(String mode) throws IOException {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("fixture.classpath", System.getProperty("java.class.path")), Fixture.class.getName(), mode).start();
    }
    @Test void malformedIpcAfterReadyReportsOneErrorAndTerminates() throws Exception {
        Process p = fixture("malformed");
        List<HelperEvent> events = new CopyOnWriteArrayList<>();
        try (HelperProcess session = new HelperProcess(p, events::add)) {
            session.ready(Duration.ofSeconds(5)).get(6, TimeUnit.SECONDS);
            assertTrue(p.waitFor(5, TimeUnit.SECONDS));
            awaitErrors(events);
            assertEquals(1, events.stream().filter(e -> e.type().equals("error")).count());
            assertFalse(events.toString().contains("TOP_SECRET"));
        }
    }
    @Test void eofAfterReadyReportsErrorAndCloses() throws Exception {
        Process p = fixture("eof");
        List<HelperEvent> events = new CopyOnWriteArrayList<>();
        try (HelperProcess session = new HelperProcess(p, events::add)) {
            session.ready(Duration.ofSeconds(5)).get(6, TimeUnit.SECONDS);
            awaitErrors(events);
            assertEquals(1, events.stream().filter(e -> e.type().equals("error")).count());
        }
    }
    @Test void closeDoesNotWaitForUnresponsiveHelperAndRejectsCommands() throws Exception {
        Process p = fixture("hang");
        HelperProcess session = new HelperProcess(p, e -> {});
        session.ready(Duration.ofSeconds(5)).get(6, TimeUnit.SECONDS);
        long before = System.nanoTime();
        session.close();
        assertTrue(Duration.ofNanos(System.nanoTime() - before).toMillis() < 300);
        assertThrows(IOException.class, session::speedtest);
        assertTrue(p.waitFor(6, TimeUnit.SECONDS));
    }
    @Test void cancellationOfReadinessTerminatesSession() throws Exception {
        Process p = fixture("slow");
        HelperProcess session = new HelperProcess(p, e -> {});
        try {
            assertTrue(session.ready(Duration.ofSeconds(10)).cancel(false));
            assertTrue(p.waitFor(4, TimeUnit.SECONDS));
            assertThrows(IOException.class, session::speedtest);
        } finally { session.close(); p.destroyForcibly(); }
    }
    @Test void sendsSpeedtestCommandToStdin() throws Exception {
        Process p = fixture("command");
        List<HelperEvent> events = new CopyOnWriteArrayList<>();
        try (HelperProcess session = new HelperProcess(p, events::add)) {
            session.ready(Duration.ofSeconds(5)).get(6, TimeUnit.SECONDS);
            session.speedtest();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (events.stream().noneMatch(e -> e.speedtest() != null) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(events.stream().anyMatch(e -> e.speedtest() != null && "complete".equals(e.speedtest().state())));
        }
    }
    @Test void startupTimeoutReportsOneErrorAndStopsUnresponsiveHelper() throws Exception {
        Process process = fixture("slow");
        List<HelperEvent> events = new CopyOnWriteArrayList<>();
        try (HelperProcess session = new HelperProcess(process, events::add)) {
            java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> session.ready(Duration.ofMillis(50)).get(5, TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.TimeoutException.class, failure.getCause());
            awaitErrors(events);
            assertEquals(1, events.stream().filter(event -> "startup_timeout".equals(event.code())).count());
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertFalse(session.isAlive());
        } finally { process.destroyForcibly(); }
    }
    @Test void successfulReadinessCancelsItsDeadline() throws Exception {
        Process process = fixture("hang");
        List<HelperEvent> events = new CopyOnWriteArrayList<>();
        try (HelperProcess session = new HelperProcess(process, events::add)) {
            assertEquals("ready", session.ready(Duration.ofSeconds(2)).get(5, TimeUnit.SECONDS).type());
            assertEquals("ready", session.ready(Duration.ofMillis(10)).get(1, TimeUnit.SECONDS).type());
            Thread.sleep(50);
            assertTrue(session.isAlive());
            assertTrue(events.stream().noneMatch(event -> "startup_timeout".equals(event.code())));
        } finally { process.destroyForcibly(); }
    }
    private static void awaitErrors(List<HelperEvent> events) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (events.stream().noneMatch(e -> e.type().equals("error")) && System.nanoTime() < deadline) Thread.sleep(10);
    }
    public static class Fixture {
        public static void main(String[] args) throws Exception {
            if ("slow".equals(args[0])) Thread.sleep(30000);
            System.out.println("{\"type\":\"ready\",\"protocolVersion\":1,\"mode\":\"host\",\"invite\":\"TOP_SECRET\"}");
            System.out.flush();
            Thread.sleep(100);
            switch(args[0]) {
                case "malformed": System.out.println("{invalid TOP_SECRET}"); System.out.flush(); Thread.sleep(30000); break;
                case "hang": Thread.sleep(30000); break;
                case "command":
                    if (!"{\"type\":\"speedtest\"}".equals(new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine())) System.exit(2);
                    System.out.println("{\"type\":\"speedtest\",\"protocolVersion\":1,\"speedtest\":{\"state\":\"complete\",\"uploadBytesPerSecond\":1000}}"); System.out.flush();
                    Thread.sleep(10000); break;
                default: break;
            }
        }
    }
}
