package com.tailscale.mclink;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import org.tukaani.xz.XZInputStream;

/** Bundled-only extraction, derived from the BSD-licensed upstream implementation. */
public final class NativeHelper {
    /** All six helpers travel as one solid LZMA archive; see scripts/build-natives.sh. */
    static final String ARCHIVE = "/assets/mclink/native/helpers.tar.xz";
    private NativeHelper() {}
    public static synchronized Path extract(Path gameDirectory, String modVersion, boolean development) throws IOException {
        if (modVersion == null || !modVersion.matches("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,127}")) throw new IOException("Invalid mod version");
        String override = System.getenv("MCLINK_HELPER");
        if (override != null && !Compatibility.isBlank(override)) {
            if (!development) throw new IOException("MCLINK_HELPER requires a development environment");
            Path executable = Paths.get(override).toAbsolutePath();
            if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(executable)) throw new IOException("Development helper is not an executable file");
            return executable;
        }
        Platform platform = Platform.current();
        String relative = platform.resourceDirectory() + "/" + platform.executableName();
        String expected = JsonFields.required(checksums(), relative, 64);
        if (!expected.matches("[0-9A-Fa-f]{64}")) throw new IOException("Invalid native checksum");
        Files.createDirectories(gameDirectory);
        Path dir = gameDirectory.toRealPath();
        for (String part : new String[]{"tailcat-for-minecraft", modVersion, platform.resourceDirectory()}) {
            dir = dir.resolve(part);
            if (Files.isSymbolicLink(dir)) throw new IOException("Native cache directory is a symbolic link");
            Files.createDirectories(dir);
        }
        Path destination = dir.resolve(platform.executableName());
        Path lockPath = dir.resolve("extract.lock");
        if (Files.isSymbolicLink(lockPath) || Files.isSymbolicLink(destination)) throw new IOException("Native cache file is a symbolic link");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock ignored = channel.lock()) {
            if (Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) && sha256(destination).equalsIgnoreCase(expected)) {
                ensureExecutable(destination, platform);
                return destination;
            }
            Path temporary = Files.createTempFile(dir, "mclink-helper-", ".tmp");
            try {
                try (InputStream in = NativeHelper.class.getResourceAsStream(ARCHIVE)) {
                    if (in == null) throw new IOException("Bundled helper archive is unavailable");
                    extractEntry(new BufferedInputStream(in, 1 << 16), relative, temporary);
                }
                if (!sha256(temporary).equalsIgnoreCase(expected)) throw new IOException("Bundled helper checksum mismatch");
                ensureExecutable(temporary, platform);
                try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
                ensureExecutable(destination, platform);
                return destination;
            } finally { Files.deleteIfExists(temporary); }
        }
    }
    /** Copies one regular member out of a compressed ustar stream without materialising the rest. */
    static void extractEntry(InputStream archive, String wanted, Path destination) throws IOException {
        try (XZInputStream xz = new XZInputStream(archive)) {
            byte[] header = new byte[512];
            while (readFully(xz, header)) {
                if (isZeroBlock(header)) continue;
                verifyHeaderChecksum(header);
                String name = entryName(header);
                long size = octal(header, 124, 12);
                int type = header[156] & 0xff;
                if ((type == 0 || type == '0' || type == ' ') && name.equals(wanted)) {
                    try (OutputStream out = Files.newOutputStream(destination, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                        copyExactly(xz, out, size);
                    }
                    return;
                }
                skipExactly(xz, size + ((512 - (size % 512)) % 512));
            }
        }
        throw new IOException("Bundled helper archive does not contain " + wanted);
    }
    private static String entryName(byte[] header) {
        String name = cString(header, 0, 100);
        String prefix = cString(header, 345, 155);
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }
    private static String cString(byte[] data, int offset, int length) {
        int end = offset;
        while (end < offset + length && data[end] != 0) end++;
        return new String(data, offset, end - offset, StandardCharsets.UTF_8);
    }
    private static long octal(byte[] data, int offset, int length) throws IOException {
        long value = 0;
        boolean seen = false;
        for (int i = offset; i < offset + length; i++) {
            int c = data[i] & 0xff;
            if (c == 0 || c == ' ') { if (seen) break; else continue; }
            if (c < '0' || c > '7') throw new IOException("Invalid tar header number");
            seen = true;
            value = (value << 3) + (c - '0');
        }
        return value;
    }
    private static void verifyHeaderChecksum(byte[] header) throws IOException {
        long stored = octal(header, 148, 8);
        long unsigned = 0, signed = 0;
        for (int i = 0; i < 512; i++) {
            byte b = (i >= 148 && i < 156) ? (byte) ' ' : header[i];
            unsigned += b & 0xff;
            signed += b;
        }
        if (stored != unsigned && stored != signed) throw new IOException("Corrupt native archive header");
    }
    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) if (b != 0) return false;
        return true;
    }
    private static boolean readFully(InputStream in, byte[] buffer) throws IOException {
        int read = 0;
        while (read < buffer.length) {
            int n = in.read(buffer, read, buffer.length - read);
            if (n < 0) { if (read == 0) return false; throw new EOFException("Truncated native archive"); }
            read += n;
        }
        return true;
    }
    private static void copyExactly(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buffer = new byte[1 << 16];
        long remaining = size;
        while (remaining > 0) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (n < 0) throw new EOFException("Truncated native archive entry");
            out.write(buffer, 0, n);
            remaining -= n;
        }
    }
    private static void skipExactly(InputStream in, long size) throws IOException {
        byte[] buffer = new byte[1 << 13];
        long remaining = size;
        while (remaining > 0) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (n < 0) throw new EOFException("Truncated native archive");
            remaining -= n;
        }
    }
    static void ensureExecutable(Path path, Platform platform) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Native helper is not a regular file");
        if (!"windows".equals(platform.os()) && !Files.isExecutable(path) && !path.toFile().setExecutable(true, true))
            throw new IOException("Could not set native helper executable mode");
        if (!Files.isExecutable(path)) throw new IOException("Native helper is not executable");
    }
    static JsonObject checksums() throws IOException {
        try (InputStream in = NativeHelper.class.getResourceAsStream("/assets/mclink/native/checksums.json")) {
            if (in == null) throw new IOException("Missing bundled native checksums");
            ByteArrayOutputStream contents = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while (contents.size() <= 1_048_576
                    && (count = in.read(buffer, 0, Math.min(buffer.length, 1_048_577 - contents.size()))) != -1) {
                contents.write(buffer, 0, count);
            }
            byte[] bytes = contents.toByteArray();
            if (bytes.length > 1_048_576) throw new IOException("Native checksum manifest is too large");
            try { return new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject(); }
            catch (RuntimeException e) { throw new IOException("Invalid native checksum manifest"); }
        }
    }
    static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[1 << 16];
                int count;
                while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >>> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
