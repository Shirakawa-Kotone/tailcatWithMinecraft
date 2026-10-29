package com.tailscale.mclink;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Converts invitation envelopes without changing the opaque Tailcat connection data.
 * This validates the envelope and encoding; the helper validates Tailcat semantics.
 */
public final class InviteCode {
    private static final String FULL_PREFIX = "mcl1_";
    private static final int MAX_FULL_LENGTH = 16384;
    private static final int MAX_TAILCAT_LENGTH = 8192;

    private InviteCode() {}

    /** Returns the original tc code, stripping only whitespace outside the user input. */
    public static String toShort(String value) {
        String input = normalized(value);
        if (input.startsWith("tc")) return checkedTailcat(input);
        if (!input.startsWith(FULL_PREFIX)) throw invalid();
        try {
            byte[] bytes = decodeUrl(input.substring(FULL_PREFIX.length()));
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            // Gson accepts a BOM before JSON; the helper's Go JSON decoder rejects it.
            if (!json.isEmpty() && json.charAt(0) == '\ufeff') throw invalid();
            try (JsonReader reader = new JsonReader(new StringReader(json))) {
                reader.setLenient(false);
                reader.beginObject();
                boolean versionSeen = false;
                String tailcat = null;
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if ("version".equals(name) && !versionSeen) {
                        if (reader.peek() != JsonToken.NUMBER || !"1".equals(reader.nextString())) {
                            throw invalid();
                        }
                        versionSeen = true;
                    } else if ("tailcat".equals(name) && tailcat == null) {
                        if (reader.peek() != JsonToken.STRING) throw invalid();
                        tailcat = checkedTailcat(reader.nextString());
                    } else {
                        throw invalid();
                    }
                }
                reader.endObject();
                if (!versionSeen || tailcat == null || reader.peek() != JsonToken.END_DOCUMENT) {
                    throw invalid();
                }
                return tailcat;
            }
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            // Gson/base64 diagnostics and their causes can contain invitation data.
            throw invalid();
        }
    }

    /** Returns a helper-compatible mcl1 envelope; existing envelopes retain their bytes. */
    public static String toFull(String value) {
        String input = normalized(value);
        String tailcat = toShort(input);
        if (input.startsWith(FULL_PREFIX)) return input;
        JsonObject envelope = new JsonObject();
        envelope.addProperty("version", 1);
        envelope.addProperty("tailcat", tailcat);
        return FULL_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(envelope.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Safe for input validation: malformed or oversized values return false. */
    public static boolean isSupported(String value) {
        try {
            toShort(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String normalized(String value) {
        if (value == null) throw invalid();
        String input = value.trim();
        if (input.isEmpty() || input.length() > MAX_FULL_LENGTH) throw invalid();
        return input;
    }

    private static String checkedTailcat(String token) {
        if (!token.startsWith("tc") || token.length() > MAX_TAILCAT_LENGTH) throw invalid();
        decodeUrl(token.substring(2));
        return token;
    }

    private static byte[] decodeUrl(String encoded) {
        if (encoded.isEmpty()) throw invalid();
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_')) throw invalid();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded)) {
                throw invalid();
            }
            return bytes;
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid invitation code");
    }
}
