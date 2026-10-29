package com.tailscale.mclink.bootstrap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The bundled matrix is exact: version ranges never imply unbuilt compatibility. */
final class AdapterIndex {
    static final int MAX_INDEX_BYTES = 1_048_576;
    final String modVersion;
    final List<Entry> entries;
    private AdapterIndex(String version, List<Entry> entries) {
        this.modVersion = version;
        this.entries = Collections.unmodifiableList(entries);
    }
    static AdapterIndex read(InputStream input) throws IOException {
        if (input == null) throw new IOException("Missing bundled Tailcat adapter index");
        try (InputStream in = input) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_INDEX_BYTES) throw new IOException("Tailcat adapter index exceeds limit");
                bytes.write(buffer, 0, count);
            }
            JsonObject root = new JsonParser().parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
            if (integer(root, "format") != 1) throw new IOException("Unsupported Tailcat adapter index format");
            String version = text(root, "modVersion", "[A-Za-z0-9][A-Za-z0-9._+\\-]{0,127}");
            JsonElement adapters = root.get("adapters");
            if (adapters == null || !adapters.isJsonArray() || adapters.getAsJsonArray().size() > 2048) throw invalid();
            List<Entry> entries = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            Set<String> claims = new HashSet<>();
            for (JsonElement item : adapters.getAsJsonArray()) {
                if (!item.isJsonObject()) throw invalid();
                JsonObject object = item.getAsJsonObject();
                String id = text(object, "id", "[A-Za-z0-9][A-Za-z0-9_-]{0,95}");
                if (!ids.add(id)) throw new IOException("Duplicate Tailcat adapter id");
                String loader = text(object, "loader", "fabric|forge|neoforge");
                String entrypoint = text(object, "entrypoint", "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
                String resource = text(object, "resource", "META-INF/tailcat/adapters/[A-Za-z0-9_-]+\\.jar");
                if (!resource.equals("META-INF/tailcat/adapters/" + id + ".jar")) throw invalid();
                String checksum = text(object, "sha256", "[0-9a-fA-F]{64}").toLowerCase(java.util.Locale.ROOT);
                int javaVersion = integer(object, "java");
                if (javaVersion < 8 || javaVersion > 99) throw invalid();
                JsonElement minecraft = object.get("minecraft");
                if (minecraft == null || !minecraft.isJsonArray() || minecraft.getAsJsonArray().size() == 0
                        || minecraft.getAsJsonArray().size() > 256) throw invalid();
                List<String> versions = new ArrayList<>();
                for (JsonElement mc : minecraft.getAsJsonArray()) {
                    if (!mc.isJsonPrimitive() || !mc.getAsJsonPrimitive().isString()) throw invalid();
                    String value = mc.getAsString();
                    if (!value.matches("[0-9]+(\\.[0-9]+){1,2}([-+][A-Za-z0-9._-]+)?") || value.length() > 96) throw invalid();
                    if (!claims.add(loader + ":" + value)) throw new IOException("Duplicate Tailcat loader/version adapter claim");
                    versions.add(value);
                }
                entries.add(new Entry(id, loader, versions, entrypoint, resource, checksum, javaVersion));
            }
            return new AdapterIndex(version, entries);
        } catch (IOException e) { throw e; }
        catch (RuntimeException e) { throw invalid(); }
    }
    Entry select(String loader, String minecraft, int javaVersion) throws IOException {
        for (Entry entry : entries) {
            if (entry.loader.equals(loader) && entry.minecraft.contains(minecraft)) {
                if (javaVersion < entry.javaVersion) throw new IOException("Tailcat for Minecraft " + minecraft
                        + " requires Java " + entry.javaVersion + "; current Java is " + javaVersion);
                return entry;
            }
        }
        throw new IOException("Tailcat universal does not include " + loader + " for Minecraft " + minecraft);
    }
    private static String text(JsonObject object, String field, String pattern) throws IOException {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw invalid();
        String value = element.getAsString();
        if (value.length() > 512 || !value.matches(pattern)) throw invalid();
        return value;
    }
    private static int integer(JsonObject object, String field) throws IOException {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) throw invalid();
        try { return element.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException e) { throw invalid(); }
    }
    private static IOException invalid() { return new IOException("Invalid bundled Tailcat adapter metadata"); }
    static final class Entry {
        final String id, loader, entrypoint, resource, sha256;
        final List<String> minecraft;
        final int javaVersion;
        private Entry(String id, String loader, List<String> minecraft, String entrypoint,
                String resource, String sha256, int javaVersion) {
            this.id = id; this.loader = loader; this.minecraft = Collections.unmodifiableList(minecraft);
            this.entrypoint = entrypoint; this.resource = resource; this.sha256 = sha256; this.javaVersion = javaVersion;
        }
    }
}
