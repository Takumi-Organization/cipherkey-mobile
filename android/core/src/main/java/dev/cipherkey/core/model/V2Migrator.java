package dev.cipherkey.core.model;

import com.google.gson.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** Convierte el contenido de una bóveda v2 (array JSON con ids enteros y fechas locales) en un documento v3. */
public final class V2Migrator {
    private V2Migrator() {}

    public static VaultDocument migrate(byte[] v2Plaintext, ZoneId zone, String deviceId) {
        JsonElement root = JsonParser.parseString(new String(v2Plaintext, StandardCharsets.UTF_8));
        if (!root.isJsonArray()) throw new IllegalArgumentException("El contenido v2 debe ser un array");
        VaultDocument doc = new VaultDocument();
        for (JsonElement el : root.getAsJsonArray()) {
            JsonObject o = el.getAsJsonObject();
            Record r = new Record();
            if (o.has("id")) r.legacyId = o.get("id").getAsInt();
            r.title = s(o, "title"); r.username = s(o, "username"); r.password = s(o, "password");
            r.url = s(o, "url"); r.notes = s(o, "notes"); r.category = s(o, "category");
            r.totpSecret = s(o, "totpSecret");
            r.favorite = o.has("favorite") && o.get("favorite").getAsBoolean();
            r.created = millis(o, "created", zone);
            r.updatedAt = o.has("modified") ? millis(o, "modified", zone) : r.created;
            r.updatedBy = deviceId;
            if (o.has("history") && o.get("history").isJsonArray()) {
                for (JsonElement h : o.getAsJsonArray("history")) {
                    JsonObject ho = h.getAsJsonObject();
                    r.history.add(new Record.HistoryItem(s(ho, "password"), millis(ho, "replacedAt", zone)));
                }
            }
            doc.records.add(r);
        }
        return doc;
    }

    private static String s(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static long millis(JsonObject o, String k, ZoneId zone) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull()) return 0;
        return LocalDateTime.parse(e.getAsString()).atZone(zone).toInstant().toEpochMilli();
    }

    /** Conversión inversa (para exportar a v2 si hiciera falta y para pruebas de ida y vuelta). */
    public static byte[] toV2(List<Record> live, ZoneId zone) {
        JsonArray arr = new JsonArray();
        int next = 1;
        for (Record r : live) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.legacyId != null ? r.legacyId : next++);
            o.addProperty("title", r.title); o.addProperty("username", r.username); o.addProperty("password", r.password);
            o.addProperty("url", r.url); o.addProperty("notes", r.notes); o.addProperty("category", r.category);
            o.addProperty("favorite", r.favorite); o.addProperty("totpSecret", r.totpSecret);
            o.addProperty("created", ldt(r.created, zone)); o.addProperty("modified", ldt(r.updatedAt, zone));
            JsonArray h = new JsonArray();
            for (Record.HistoryItem i : r.history) {
                JsonObject x = new JsonObject();
                x.addProperty("password", i.password());
                x.addProperty("replacedAt", ldt(i.replacedAt(), zone));
                h.add(x);
            }
            o.add("history", h);
            arr.add(o);
        }
        return arr.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String ldt(long ms, ZoneId zone) {
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), zone).toString();
    }
}
