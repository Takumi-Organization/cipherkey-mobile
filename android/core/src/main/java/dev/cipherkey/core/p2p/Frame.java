package dev.cipherkey.core.p2p;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;

/** Trama de aplicación: un objeto JSON con un campo {@code t} (tipo). Viaja siempre cifrada por {@link SecureChannel}. */
public final class Frame {
    public final JsonObject json = new JsonObject();

    public Frame() {}

    public Frame(String type) { json.addProperty("t", type); }

    public String type() { return json.has("t") ? json.get("t").getAsString() : ""; }

    public Frame put(String k, String v) { json.addProperty(k, v); return this; }

    public Frame put(String k, long v) { json.addProperty(k, v); return this; }

    public Frame put(String k, boolean v) { json.addProperty(k, v); return this; }

    public Frame put(String k, com.google.gson.JsonElement v) { json.add(k, v); return this; }

    public String get(String k) {
        if (!json.has(k)) throw new IllegalArgumentException("Falta el campo " + k);
        return json.get(k).getAsString();
    }

    byte[] encode() { return json.toString().getBytes(StandardCharsets.UTF_8); }

    static Frame decode(byte[] b) {
        Frame f = new Frame();
        JsonObject o = JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject();
        for (var e : o.entrySet()) f.json.add(e.getKey(), e.getValue());
        return f;
    }
}
