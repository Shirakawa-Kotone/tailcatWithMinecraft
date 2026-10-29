package com.tailscale.mclink.bootstrap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BootstrapTest {
    @TempDir Path directory;
    private static final String FIXTURE = "com.tailscale.mclink.bootstrap.fixture.IsolatedAdapter";
    private static final String RESOURCE = "META-INF/tailcat/adapters/fabric1165.jar";
    private String index(String items) { return "{\"format\":1,\"modVersion\":\"0.3.0\",\"adapters\":[" + items + "]}"; }
    private String adapter(String loader, String mc, int javaVersion, String sha) {
        return "{\"id\":\"fabric1165\",\"loader\":\"" + loader + "\",\"minecraft\":[\"" + mc
                + "\"],\"entrypoint\":\"" + FIXTURE + "\",\"resource\":\"" + RESOURCE + "\",\"sha256\":\"" + sha + "\",\"java\":" + javaVersion + "}";
    }
    private AdapterIndex parse(String json) throws IOException { return AdapterIndex.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))); }
    private static String repeated(char c, int count) { char[] chars = new char[count]; java.util.Arrays.fill(chars, c); return new String(chars); }
    @Test void selectsExactLoaderAndVersionAndEnforcesJavaFloor() throws Exception {
        AdapterIndex parsed = parse(index(adapter("fabric", "1.16.5", 8, repeated('a', 64))));
        assertEquals("fabric1165", parsed.select("fabric", "1.16.5", 8).id);
        assertThrows(IOException.class, () -> parsed.select("forge", "1.16.5", 8));
        assertThrows(IOException.class, () -> parsed.select("fabric", "1.16.4", 8));
        AdapterIndex modern = parse(index(adapter("fabric", "26.3", 25, repeated('a', 64))));
        assertThrows(IOException.class, () -> modern.select("fabric", "26.3", 21));
        assertEquals(25, modern.select("fabric", "26.3", 25).javaVersion);
    }
    @Test void rejectsDuplicateVersionClaimsUnsafePathsAndMalformedMetadata() throws Exception {
        String valid = adapter("fabric", "1.16.5", 8, repeated('a', 64));
        assertThrows(IOException.class, () -> parse(index(valid + "," + valid)));
        assertThrows(IOException.class, () -> parse(index(valid.replace(RESOURCE, "../escape.jar"))));
        assertThrows(IOException.class, () -> parse(index(valid.replace("\"java\":8", "\"java\":\"8\""))));
        assertThrows(IOException.class, () -> parse(index(valid.replace("\"1.16.5\"", "\">=1.16\""))));
        assertThrows(IOException.class, () -> parse(index(valid).replace("\"format\":1", "\"format\":2")));
        assertThrows(IOException.class, () -> parse(repeated(' ', 1_048_577)));
    }
    @Test void cacheRepairsTamperingAndRejectsResourceChecksumMismatch() throws Exception {
        byte[] bytes = fixtureJar();
        AdapterIndex.Entry entry = parse(index(adapter("fabric", "1.16.5", 8, AdapterCache.sha256(bytes)))).select("fabric", "1.16.5", 8);
        Path file = AdapterCache.extract(directory, "0.3.0", entry, name -> new ByteArrayInputStream(bytes));
        assertArrayEquals(bytes, Files.readAllBytes(file));
        Files.write(file, new byte[]{7});
        assertEquals(file, AdapterCache.extract(directory, "0.3.0", entry, name -> new ByteArrayInputStream(bytes)));
        assertArrayEquals(bytes, Files.readAllBytes(file));
        Files.delete(file);
        assertThrows(IOException.class, () -> AdapterCache.extract(directory, "0.3.0", entry, name -> new ByteArrayInputStream(new byte[]{9})));
        assertFalse(Files.exists(file));
    }
    @Test void refusesSymlinkedCacheDirectoriesAndOversizedAdapters() throws Exception {
        byte[] bytes = fixtureJar();
        AdapterIndex.Entry entry = parse(index(adapter("fabric", "1.16.5", 8, AdapterCache.sha256(bytes)))).select("fabric", "1.16.5", 8);
        Path target = Files.createDirectory(directory.resolve("outside"));
        Files.createSymbolicLink(directory.resolve("tailcat-for-minecraft"), target);
        assertThrows(IOException.class, () -> AdapterCache.extract(directory, "0.3.0", entry, name -> new ByteArrayInputStream(bytes)));
        Files.delete(directory.resolve("tailcat-for-minecraft"));
        assertThrows(IOException.class, () -> AdapterCache.extract(directory, "0.3.0", entry, name -> new ByteArrayInputStream(new byte[8 * 1024 * 1024 + 1])));
    }
    @Test void launchesRealNestedAdapterWithSharedCoreInParent() throws Exception {
        byte[] bytes = fixtureJar();
        AdapterIndex.Entry entry = parse(index(adapter("fabric", "1.16.5", 8, AdapterCache.sha256(bytes)))).select("fabric", "1.16.5", 8);
        Path file = AdapterCache.extract(directory, "0.3.0", entry, name -> new ByteArrayInputStream(bytes));
        ClassLoader parent = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(FIXTURE)) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        System.clearProperty("tailcat.fixture.constructor");
        System.clearProperty("tailcat.fixture.fabric");
        try (AdapterLoader loaded = AdapterLoader.launch(file, entry, parent)) {
            assertEquals("linux-amd64", System.getProperty("tailcat.fixture.constructor"));
            assertEquals("initialized", System.getProperty("tailcat.fixture.fabric"));
            assertNotSame(parent, loaded.instance.getClass().getClassLoader());
        }
    }
    @Test void rejectsEntrypointAccidentallyPresentInParent() throws Exception {
        byte[] bytes = fixtureJar();
        AdapterIndex.Entry entry = parse(index(adapter("forge", "1.16.5", 8, AdapterCache.sha256(bytes)))).select("forge", "1.16.5", 8);
        Path file = AdapterCache.extract(directory, "0.3.0", entry, name -> new ByteArrayInputStream(bytes));
        assertThrows(IOException.class, () -> AdapterLoader.launch(file, entry, getClass().getClassLoader()));
    }
    @Test void javaVersionParsingAndProducedJarExcludeRuntimeSdkStubs() throws Exception {
        assertEquals(8, RuntimeEnvironment.javaMajor("1.8.0_512"));
        assertEquals(25, RuntimeEnvironment.javaMajor("25.0.1"));
        try (JarFile jar = new JarFile(System.getProperty("bootstrap.jar"))) {
            assertNotNull(jar.getJarEntry("com/tailscale/mclink/bootstrap/FabricBootstrap.class"));
            assertNull(jar.getJarEntry("net/fabricmc/api/ClientModInitializer.class"));
            assertNull(jar.getJarEntry("net/minecraftforge/fml/common/Mod.class"));
            assertNull(jar.getJarEntry("net/neoforged/fml/common/Mod.class"));
        }
    }
    @Test void completeBootstrapDetectsGameDescriptorAndStartsExactlyOnce() throws Exception {
        byte[] bytes = fixtureJar();
        Path resources = Files.createDirectory(directory.resolve("resources"));
        Path indexFile = resources.resolve("META-INF/tailcat/adapters.json");
        Files.createDirectories(indexFile.getParent());
        Files.write(indexFile, index(adapter("fabric", "1.16.5", 8, AdapterCache.sha256(bytes))).getBytes(StandardCharsets.UTF_8));
        Files.write(resources.resolve("version.json"), "{\"id\":\"1.16.5\"}".getBytes(StandardCharsets.UTF_8));
        Path jar = resources.resolve(RESOURCE);
        Files.createDirectories(jar.getParent());
        Files.write(jar, bytes);
        ClassLoader parent = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("com.tailscale.mclink.bootstrap.")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        String previousDirectory = System.getProperty("user.dir");
        System.setProperty("user.dir", directory.toString());
        System.setProperty("tailcat.fixture.instances", "0");
        try (java.net.URLClassLoader bootstrap = new java.net.URLClassLoader(new java.net.URL[]{
                new java.io.File(System.getProperty("bootstrap.jar")).toURI().toURL(), resources.toUri().toURL()}, parent)) {
            Class<?> runtime = Class.forName("com.tailscale.mclink.bootstrap.BootstrapRuntime", true, bootstrap);
            runtime.getMethod("launch", String.class).invoke(null, "fabric");
            runtime.getMethod("launch", String.class).invoke(null, "fabric");
            assertEquals("1", System.getProperty("tailcat.fixture.instances"));
            assertEquals("initialized", System.getProperty("tailcat.fixture.fabric"));
            assertTrue(Files.isDirectory(directory.resolve("tailcat-for-minecraft/0.3.0/adapters")));
            java.lang.reflect.Field active = runtime.getDeclaredField("activeAdapter");
            active.setAccessible(true);
            ((java.io.Closeable) active.get(null)).close();
        } finally { System.setProperty("user.dir", previousDirectory); }
    }
    @Test void oldForgeEntrypointSelectsEarlyNeoForgeFromItsPublishedGroup() throws Exception {
        byte[] bytes = fixtureJar();
        Path resources = Files.createDirectory(directory.resolve("early-neoforge-resources"));
        Path indexFile = resources.resolve("META-INF/tailcat/adapters.json");
        Files.createDirectories(indexFile.getParent());
        Files.write(indexFile, index(adapter("neoforge", "1.20.1", 8, AdapterCache.sha256(bytes))).getBytes(StandardCharsets.UTF_8));
        Files.write(resources.resolve("version.json"), "{\"id\":\"1.16.5\"}".getBytes(StandardCharsets.UTF_8));
        Path jar = resources.resolve(RESOURCE);
        Files.createDirectories(jar.getParent());
        Files.write(jar, bytes);
        String sdkPath = "net/minecraftforge/versions/forge/ForgeVersion.class";
        Path sdk = resources.resolve(sdkPath);
        Files.createDirectories(sdk.getParent());
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(sdkPath)) { Files.copy(input, sdk); }
        ClassLoader parent = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("com.tailscale.mclink.bootstrap.") || name.equals("net.minecraftforge.versions.forge.ForgeVersion"))
                    throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        String previousDirectory = System.getProperty("user.dir");
        String previousGroup = System.getProperty("tailcat.fixture.forgeGroup");
        String previousGameDirectory = System.getProperty("tailcat.fixture.gameDir");
        Path loaderGameDirectory = directory.resolve("loader-game-directory");
        System.setProperty("tailcat.fixture.gameDir", loaderGameDirectory.toString());
        System.setProperty("user.dir", directory.toString());
        System.setProperty("tailcat.fixture.forgeGroup", "net.neoforged");
        System.setProperty("tailcat.fixture.instances", "0");
        try (java.net.URLClassLoader bootstrap = new java.net.URLClassLoader(new java.net.URL[]{
                new java.io.File(System.getProperty("bootstrap.jar")).toURI().toURL(), resources.toUri().toURL()}, parent)) {
            Class<?> forgeEntrypoint = Class.forName("com.tailscale.mclink.bootstrap.ForgeBootstrap", true, bootstrap);
            forgeEntrypoint.getConstructor().newInstance();
            assertEquals("1", System.getProperty("tailcat.fixture.instances"));
            assertTrue(Files.isDirectory(loaderGameDirectory.resolve("tailcat-for-minecraft/0.3.0/adapters")));
            assertFalse(Files.exists(directory.resolve("tailcat-for-minecraft")));
            Class<?> runtime = Class.forName("com.tailscale.mclink.bootstrap.BootstrapRuntime", false, bootstrap);
            java.lang.reflect.Field active = runtime.getDeclaredField("activeAdapter");
            active.setAccessible(true);
            ((java.io.Closeable) active.get(null)).close();
        } finally {
            System.setProperty("user.dir", previousDirectory);
            if (previousGameDirectory == null) System.clearProperty("tailcat.fixture.gameDir");
            else System.setProperty("tailcat.fixture.gameDir", previousGameDirectory);
            if (previousGroup == null) System.clearProperty("tailcat.fixture.forgeGroup");
            else System.setProperty("tailcat.fixture.forgeGroup", previousGroup);
        }
    }
    @Test void forgeBrandingUsesGroupAndFallsBackWhenLegacyApiHasNoGetGroup() throws Exception {
        String previous = System.getProperty("tailcat.fixture.forgeGroup");
        try {
            System.setProperty("tailcat.fixture.forgeGroup", "net.minecraftforge");
            assertEquals("forge", RuntimeEnvironment.forgeLoader(getClass().getClassLoader()));
            System.setProperty("tailcat.fixture.forgeGroup", "net.neoforged");
            assertEquals("neoforge", RuntimeEnvironment.forgeLoader(getClass().getClassLoader()));
            String sdkPath = "net/minecraftforge/versions/forge/ForgeVersion.class";
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(sdkPath)) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            }
            // Same valid SDK class with an older public API: rename the absent method in its constant pool.
            byte[] olderApi = new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
                    .replace("getGroup", "oldGroup").getBytes(StandardCharsets.ISO_8859_1);
            Path resources = Files.createDirectory(directory.resolve("old-forge-sdk"));
            Path sdk = resources.resolve(sdkPath);
            Files.createDirectories(sdk.getParent());
            Files.write(sdk, olderApi);
            ClassLoader parent = new ClassLoader(getClass().getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (name.equals("net.minecraftforge.versions.forge.ForgeVersion")) throw new ClassNotFoundException(name);
                    return super.loadClass(name, resolve);
                }
            };
            try (java.net.URLClassLoader oldForge = new java.net.URLClassLoader(new java.net.URL[]{resources.toUri().toURL()}, parent)) {
                Class<?> version = Class.forName("net.minecraftforge.versions.forge.ForgeVersion", true, oldForge);
                assertThrows(NoSuchMethodException.class, () -> version.getMethod("getGroup"));
                assertEquals("forge", RuntimeEnvironment.forgeLoader(oldForge));
            }
        } finally {
            if (previous == null) System.clearProperty("tailcat.fixture.forgeGroup");
            else System.setProperty("tailcat.fixture.forgeGroup", previous);
        }
    }
    @Test void bootstrapJarContainsOnlyJava8RuntimeBytecode() throws Exception {
        try (JarFile jar = new JarFile(System.getProperty("bootstrap.jar"))) {
            java.util.Enumeration<JarEntry> entries = jar.entries();
            int classes = 0;
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                classes++;
                try (java.io.DataInputStream in = new java.io.DataInputStream(jar.getInputStream(entry))) {
                    assertEquals(0xcafebabe, in.readInt());
                    in.readUnsignedShort();
                    assertEquals(52, in.readUnsignedShort(), entry.getName());
                }
            }
            assertTrue(classes >= 8);
        }
    }
    private byte[] fixtureJar() throws IOException {
        String name = FIXTURE.replace('.', '/') + ".class";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out); InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            jar.putNextEntry(new JarEntry(name));
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) jar.write(buffer, 0, n);
            jar.closeEntry();
        }
        return out.toByteArray();
    }
}
