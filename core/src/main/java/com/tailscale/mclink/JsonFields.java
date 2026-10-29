package com.tailscale.mclink;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigDecimal;

/** Strict IPC values: Gson's coercing accessors must not accept strings as numbers. */
final class JsonFields {
    private JsonFields() {}
    static String string(JsonObject o, String key, int limit) throws IOException {
        JsonElement value = o.get(key);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid();
        String text = value.getAsString();
        if (text.length() > limit || text.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid();
        return text;
    }
    static String required(JsonObject o, String key, int limit) throws IOException {
        String value = string(o, key, limit);
        if (value == null || Compatibility.isBlank(value)) throw invalid();
        return value;
    }
    static BigDecimal number(JsonObject o, String key) throws IOException {
        JsonElement value = o.get(key);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid();
        try {
            BigDecimal number = value.getAsBigDecimal();
            if (number.signum() < 0 || !Double.isFinite(number.doubleValue())) throw invalid();
            return number;
        } catch (NumberFormatException e) { throw invalid(); }
    }
    static Double decimal(JsonObject o, String key) throws IOException {
        BigDecimal value = number(o, key);
        return value == null ? null : value.doubleValue();
    }
    static Long integer(JsonObject o, String key) throws IOException {
        BigDecimal value = number(o, key);
        try { return value == null ? null : value.longValueExact(); }
        catch (ArithmeticException e) { throw invalid(); }
    }
    static JsonObject object(JsonObject o, String key) throws IOException {
        JsonElement value = o.get(key);
        if (value == null || !value.isJsonObject()) throw invalid();
        return value.getAsJsonObject();
    }
    static IOException invalid() { return new IOException("Invalid helper IPC value"); }
}
