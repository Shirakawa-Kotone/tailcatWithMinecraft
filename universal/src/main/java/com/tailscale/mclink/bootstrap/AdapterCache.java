package com.tailscale.mclink.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Extracts only the selected bundled adapter, never a remote artifact. */
final class AdapterCache {
    static final int MAX_ADAPTER_BYTES = 8 * 1024 * 1024;
    interface Resources { InputStream open(String name) throws IOException; }
    private AdapterCache() {}
    static synchronized Path extract(Path gameDirectory, String version, AdapterIndex.Entry entry, Resources resources) throws IOException {
        if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,127}")) throw new IOException("Invalid Tailcat version");
        Files.createDirectories(gameDirectory);
        Path directory = gameDirectory.toRealPath();
        for (String part : new String[]{"tailcat-for-minecraft", version, "adapters"}) {
            directory = directory.resolve(part);
            if (Files.isSymbolicLink(directory)) throw new IOException("Tailcat adapter cache directory is a symbolic link");
            Files.createDirectories(directory);
        }
        Path destination = directory.resolve(entry.id + "-" + entry.sha256.substring(0, 16) + ".jar");
        Path lockPath = directory.resolve("extract.lock");
        if (Files.isSymbolicLink(destination) || Files.isSymbolicLink(lockPath)) throw new IOException("Tailcat adapter cache file is a symbolic link");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock ignored = channel.lock()) {
            if (Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) && Files.size(destination) <= MAX_ADAPTER_BYTES
                    && digest(destination).equals(entry.sha256)) return destination;
            Path temporary = Files.createTempFile(directory, "adapter-", ".tmp");
            try {
                MessageDigest digest = digest();
                try (InputStream input = resources.open(entry.resource);
                     OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    if (input == null) throw new IOException("Bundled Tailcat adapter is missing: " + entry.id);
                    byte[] buffer = new byte[16384];
                    int count, total = 0;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_ADAPTER_BYTES) throw new IOException("Bundled Tailcat adapter exceeds size limit");
                        output.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                    }
                }
                if (!hex(digest.digest()).equals(entry.sha256)) throw new IOException("Bundled Tailcat adapter checksum mismatch: " + entry.id);
                try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
                return destination;
            } finally { Files.deleteIfExists(temporary); }
        }
    }
    static String sha256(byte[] data) { return hex(digest().digest(data)); }
    private static String digest(Path path) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) result.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return result.toString();
    }
}
