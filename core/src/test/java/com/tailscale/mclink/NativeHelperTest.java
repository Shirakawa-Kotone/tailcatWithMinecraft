package com.tailscale.mclink;

import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class NativeHelperTest {
    @TempDir Path directory;
    @Test void checksumUsesActualFileContents() throws Exception {
        Path file = directory.resolve("input");
        Files.write(file, "abc".getBytes(StandardCharsets.UTF_8));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", NativeHelper.sha256(file));
    }
    @Test void rejectsVersionTraversalBeforeExtraction() {
        assertThrows(IOException.class, () -> NativeHelper.extract(directory, "../escape", false));
    }
    @Test void verifiesCachedFileExecutableModeAndRejectsSymlinks() throws Exception {
        Path file = directory.resolve("cached");
        Files.write(file, "fixture".getBytes(StandardCharsets.UTF_8));
        file.toFile().setExecutable(false, false);
        NativeHelper.ensureExecutable(file, Platform.parse("Linux", "amd64"));
        assertTrue(Files.isExecutable(file));
        Path link = directory.resolve("link");
        Files.createSymbolicLink(link, file);
        assertThrows(IOException.class, () -> NativeHelper.ensureExecutable(link, Platform.current()));
    }
    @Test void extractsOnlyRequestedMemberFromSolidArchive() throws Exception {
        Map<String, byte[]> members = new LinkedHashMap<>();
        members.put("darwin-arm64/mclink-helper", "first".getBytes(StandardCharsets.UTF_8));
        members.put("linux-amd64/mclink-helper", "second-payload".getBytes(StandardCharsets.UTF_8));
        members.put("windows-amd64/mclink-helper.exe", "third".getBytes(StandardCharsets.UTF_8));
        Path output = directory.resolve("extracted");
        Files.write(output, "stale".getBytes(StandardCharsets.UTF_8));
        NativeHelper.extractEntry(new ByteArrayInputStream(archive(members)), "linux-amd64/mclink-helper", output);
        assertArrayEquals(members.get("linux-amd64/mclink-helper"), Files.readAllBytes(output));
    }
    @Test void reportsMissingMemberAndTruncatedArchive() throws Exception {
        Map<String, byte[]> members = java.util.Collections.singletonMap("darwin-arm64/mclink-helper", new byte[]{1, 2, 3});
        Path output = directory.resolve("missing");
        assertThrows(IOException.class, () -> NativeHelper.extractEntry(new ByteArrayInputStream(archive(members)), "linux-arm64/mclink-helper", output));
        byte[] valid = archive(members);
        byte[] truncated = java.util.Arrays.copyOf(valid, valid.length / 2);
        assertThrows(IOException.class, () -> NativeHelper.extractEntry(new ByteArrayInputStream(truncated), "darwin-arm64/mclink-helper", output));
    }
    private static byte[] archive(Map<String, byte[]> members) throws IOException {
        byte[] tar = tar(members);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LZMA2Options options = new LZMA2Options();
        try (XZOutputStream xz = new XZOutputStream(out, options)) { xz.write(tar); }
        return out.toByteArray();
    }
    private static byte[] tar(Map<String, byte[]> members) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> member : members.entrySet()) {
            byte[] header = new byte[512];
            byte[] name = member.getKey().getBytes(StandardCharsets.UTF_8);
            System.arraycopy(name, 0, header, 0, name.length);
            writeOctal(header, 100, 8, 0644);
            writeOctal(header, 108, 8, 0);
            writeOctal(header, 116, 8, 0);
            writeOctal(header, 124, 12, member.getValue().length);
            writeOctal(header, 136, 12, 0);
            header[156] = '0';
            header[257] = 'u'; header[258] = 's'; header[259] = 't'; header[260] = 'a'; header[261] = 'r';
            header[263] = '0'; header[264] = '0';
            for (int i = 148; i < 156; i++) header[i] = ' ';
            int sum = 0;
            for (byte b : header) sum += b & 0xff;
            writeOctal(header, 148, 8, sum);
            out.write(header);
            out.write(member.getValue());
            int padding = (512 - (member.getValue().length % 512)) % 512;
            out.write(new byte[padding]);
        }
        out.write(new byte[1024]);
        return out.toByteArray();
    }
    private static void writeOctal(byte[] header, int offset, int length, long value) {
        byte[] digits = Long.toOctalString(value).getBytes(StandardCharsets.US_ASCII);
        int pad = length - 1 - digits.length;
        for (int i = 0; i < pad; i++) header[offset + i] = '0';
        System.arraycopy(digits, 0, header, offset + pad, digits.length);
        header[offset + length - 1] = 0;
    }
}
