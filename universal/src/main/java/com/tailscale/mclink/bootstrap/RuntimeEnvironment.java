package com.tailscale.mclink.bootstrap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/** Uses public loader APIs through reflection without introducing Minecraft linkage. */
final class RuntimeEnvironment {
    private RuntimeEnvironment() {}
    /** NeoForge 47.1.x keeps Forge's annotation/package names but publishes its own group. */
    static String forgeLoader(ClassLoader classes) {
        try {
            Class<?> version = Class.forName("net.minecraftforge.versions.forge.ForgeVersion", true, classes);
            Object group = version.getMethod("getGroup").invoke(null);
            if (group instanceof String && ((String) group).contains("neoforged")) return "neoforge";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Older Forge versions do not expose getGroup; they remain Forge.
        }
        return "forge";
    }
    private static String fmlPrefix(String loader, ClassLoader classes) {
        return "neoforge".equals(loader) && !"neoforge".equals(forgeLoader(classes))
                ? "net.neoforged" : "net.minecraftforge";
    }
    static String minecraft(String loader, ClassLoader classes) throws IOException {
        if ("fabric".equals(loader)) {
            try {
                Class<?> api = Class.forName("net.fabricmc.loader.api.FabricLoader", true, classes);
                Object fabric = api.getMethod("getInstance").invoke(null);
                Object container = ((Optional<?>) api.getMethod("getModContainer", String.class).invoke(fabric, "minecraft")).get();
                Class<?> containerApi = Class.forName("net.fabricmc.loader.api.ModContainer", true, classes);
                Object metadata = containerApi.getMethod("getMetadata").invoke(container);
                Class<?> metadataApi = Class.forName("net.fabricmc.loader.api.metadata.ModMetadata", true, classes);
                Object version = metadataApi.getMethod("getVersion").invoke(metadata);
                Class<?> versionApi = Class.forName("net.fabricmc.loader.api.Version", true, classes);
                return checkedVersion((String) versionApi.getMethod("getFriendlyString").invoke(version));
            } catch (ReflectiveOperationException | RuntimeException ignored) { /* fall back to the game descriptor */ }
        }
        if (!"fabric".equals(loader) && "net.minecraftforge".equals(fmlPrefix(loader, classes))) {
            try {
                Class<?> mcp = Class.forName("net.minecraftforge.versions.mcp.MCPVersion", true, classes);
                return checkedVersion((String) mcp.getMethod("getMCVersion").invoke(null));
            } catch (ReflectiveOperationException | RuntimeException ignored) { /* fall back to the game descriptor */ }
        }
        try (InputStream in = classes.getResourceAsStream("version.json")) {
            if (in != null) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (bytes.size() + count > 65_536) throw new IOException("Minecraft version descriptor exceeds limit");
                    bytes.write(buffer, 0, count);
                }
                JsonObject descriptor = new JsonParser().parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
                JsonElement id = descriptor.get("id");
                if (id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()) return checkedVersion(id.getAsString());
            }
        } catch (RuntimeException ignored) { /* versionInfo is another public modern FML API */ }
        String prefix = fmlPrefix(loader, classes);
        try {
            Class<?> fml = Class.forName(prefix + ".fml.loading.FMLLoader", true, classes);
            Object info = fml.getMethod("versionInfo").invoke(null);
            return checkedVersion((String) info.getClass().getMethod("mcVersion").invoke(info));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            throw new IOException("Cannot detect the Minecraft version for Tailcat " + loader);
        }
    }
    static Path gameDirectory(String loader, ClassLoader classes) {
        try {
            if ("fabric".equals(loader)) {
                Class<?> api = Class.forName("net.fabricmc.loader.api.FabricLoader", true, classes);
                Object fabric = api.getMethod("getInstance").invoke(null);
                return (Path) api.getMethod("getGameDir").invoke(fabric);
            }
            String prefix = fmlPrefix(loader, classes);
            Class<?> paths = Class.forName(prefix + ".fml.loading.FMLPaths", true, classes);
            Object game = paths.getField("GAMEDIR").get(null);
            return (Path) paths.getMethod("get").invoke(game);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Paths.get(System.getProperty("user.dir", "."));
        }
    }
    static int javaMajor(String version) throws IOException {
        try {
            String value = version.startsWith("1.") ? version.substring(2) : version;
            int end = 0;
            while (end < value.length() && Character.isDigit(value.charAt(end))) end++;
            int major = Integer.parseInt(value.substring(0, end));
            if (major < 8) throw new IOException("Tailcat requires Java 8 or newer");
            return major;
        } catch (RuntimeException invalid) { throw new IOException("Cannot detect Java runtime version"); }
    }
    private static String checkedVersion(String value) throws IOException {
        if (value == null || value.length() > 96 || !value.matches("[0-9]+(\\.[0-9]+){1,2}([-+][A-Za-z0-9._-]+)?"))
            throw new IOException("Invalid Minecraft runtime version");
        return value;
    }
}
