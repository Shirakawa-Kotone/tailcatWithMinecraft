package com.tailscale.mclink;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InviteCodeTest {
    // A synthetic CBOR map with a non-secret, unusable all-zero public key and region 1.
    private static final String SHORT = syntheticToken();

    @Test void legacyRoundTripPreservesOriginalEnvelope() {
        String full = envelope("{\"tailcat\":\"" + SHORT + "\", \"version\":1}");
        assertEquals(SHORT, InviteCode.toShort(" \n" + full + "\t"));
        assertEquals(full, InviteCode.toFull(" \n" + full + "\t"));
        assertTrue(InviteCode.isSupported(full));
    }

    @Test void shortRoundTripProducesHelperCompatibleEnvelope() {
        String expected = envelope("{\"version\":1,\"tailcat\":\"" + SHORT + "\"}");
        assertEquals(expected, InviteCode.toFull(" \t" + SHORT + "\n"));
        assertEquals(SHORT, InviteCode.toShort(InviteCode.toFull(SHORT)));
        assertEquals(SHORT, InviteCode.toShort(SHORT));
        assertTrue(InviteCode.isSupported(SHORT));
    }

    @Test void preservesAllOpaqueTailcatBytes() {
        // Future/full-region tokens must not be rebuilt from selected fields.
        byte[] opaque = new byte[2048];
        for (int i = 0; i < opaque.length; i++) opaque[i] = (byte) i;
        String token = "tc" + Base64.getUrlEncoder().withoutPadding().encodeToString(opaque);
        assertEquals(token, InviteCode.toShort(InviteCode.toFull(token)));
    }

    @Test void rejectsMalformedPrefixesBase64AndWhitespace() {
        for (String bad : new String[]{null, "", "  ", "tc", "tcA", "tcAB", "tcAA=", "tcAA+_",
                "TC" + SHORT.substring(2), "tcAA\nAA", "https://example.invalid/" + SHORT,
                "mcl1_", "mcl1_A", "mcl1_e30=", "mcl2_e30", "mcl1_e30!"}) {
            reject(bad);
        }
    }

    @Test void rejectsWrongVersionTypesMissingDuplicateOrUnknownFields() {
        for (String json : new String[]{
                "{}", "[]", "null", "{\"version\":1}", "{\"tailcat\":\"" + SHORT + "\"}",
                "{\"version\":2,\"tailcat\":\"" + SHORT + "\"}",
                "{\"version\":\"1\",\"tailcat\":\"" + SHORT + "\"}",
                "{\"version\":1.0,\"tailcat\":\"" + SHORT + "\"}",
                "{\"version\":true,\"tailcat\":\"" + SHORT + "\"}",
                "{\"version\":null,\"tailcat\":\"" + SHORT + "\"}",
                "{\"version\":1,\"tailcat\":null}", "{\"version\":1,\"tailcat\":23}",
                "{\"version\":1,\"tailcat\":[]}", "{\"version\":1,\"tailcat\":\"tcA\"}",
                "{\"version\":1,\"tailcat\":\" " + SHORT + "\"}",
                "{\"version\":1,\"version\":1,\"tailcat\":\"" + SHORT + "\"}",
                "{\"version\":1,\"tailcat\":\"" + SHORT + "\",\"tailcat\":\"" + SHORT + "\"}",
                "{\"version\":1,\"tailcat\":\"" + SHORT + "\",\"extra\":0}"}) {
            reject(envelope(json));
        }
    }

    @Test void rejectsNonJsonAndTrailingDataWithoutLeakingInputs() {
        for (String json : new String[]{
                "{version:1,tailcat:'PRIVATE_INVITE_MARKER'}", "/*PRIVATE_INVITE_MARKER*/{}",
                "{\"version\":1,\"tailcat\":\"PRIVATE_INVITE_MARKER\"}",
                "{\"version\":1,\"tailcat\":\"" + SHORT + "\"} {}",
                "{\"version\":1,\"tailcat\":\"" + SHORT + "\",}",
                "{\"version\":1,\"tailcat\":\"" + SHORT + "\""}) {
            reject(envelope(json));
        }
        byte[] malformedUtf8 = {(byte) 0xc3, (byte) 0x28};
        reject("mcl1_" + Base64.getUrlEncoder().withoutPadding().encodeToString(malformedUtf8));
    }

    @Test void rejectsBomThatTheGoHelperCannotParse() {
        reject(envelope("\ufeff{\"version\":1,\"tailcat\":\"" + SHORT + "\"}"));
    }

    @Test void acceptsTokenAtTheLengthLimit() {
        char[] payload = new char[8190];
        Arrays.fill(payload, 'A');
        String token = "tc" + new String(payload);
        assertTrue(InviteCode.isSupported(token));
        assertEquals(token, InviteCode.toShort(InviteCode.toFull(token)));
    }

    @Test void rejectsOversizedTokensAndEnvelopes() {
        char[] oversized = new char[16385];
        Arrays.fill(oversized, 'A');
        reject("mcl1_" + new String(oversized));
        char[] largePayload = new char[8192];
        Arrays.fill(largePayload, 'A');
        String token = "tc" + new String(largePayload);
        reject(token);
        reject(envelope("{\"version\":1,\"tailcat\":\"" + token + "\"}"));
    }

    @Test void emittedClassLoadsWithJava8Bytecode() throws Exception {
        try (java.io.DataInputStream data = new java.io.DataInputStream(
                InviteCode.class.getResourceAsStream("InviteCode.class"))) {
            assertEquals(0xcafebabe, data.readInt());
            data.readUnsignedShort();
            assertEquals(52, data.readUnsignedShort());
        }
    }

    private static void reject(String value) {
        assertFalse(InviteCode.isSupported(value));
        for (boolean shorten : new boolean[]{false, true}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> { if (shorten) InviteCode.toShort(value); else InviteCode.toFull(value); });
            assertFalse(failure.toString().contains("PRIVATE_INVITE_MARKER"));
            if (value != null && value.length() > 16) assertFalse(failure.toString().contains(value));
            assertNull(failure.getCause(), "Parser causes can contain private invitation data");
        }
    }

    private static String envelope(String json) {
        return "mcl1_" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String syntheticToken() {
        byte[] cbor = new byte[40];
        cbor[0] = (byte) 0xa2;
        cbor[1] = 0x61;
        cbor[2] = 0x70;
        cbor[3] = 0x58;
        cbor[4] = 0x20;
        cbor[37] = 0x61;
        cbor[38] = 0x69;
        cbor[39] = 0x01;
        return "tc" + Base64.getUrlEncoder().withoutPadding().encodeToString(cbor);
    }
}
