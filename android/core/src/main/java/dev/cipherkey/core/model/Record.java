package dev.cipherkey.core.model;

import com.google.gson.*;

import java.util.*;

/**
 * Credencial sincronizable. Tiempos en milisegundos UTC (epoch). {@code extra} conserva los campos que esta versión no
 * conoce, para que un cliente antiguo no los destruya al guardar. Un registro borrado es una lápida: {@code deleted=true}
 * con el contenido vaciado.
 */
public final class Record {
    public record Custom(String name, String value, boolean secret) {}
    public record HistoryItem(String password, long replacedAt) {}

    private static final Set<String> KNOWN = Set.of("uuid", "rev", "updatedAt", "updatedBy", "deleted", "created",
            "title", "username", "password", "url", "notes", "category", "favorite", "totpSecret", "tags", "custom",
            "history", "conflict", "legacyId");

    public String uuid = UUID.randomUUID().toString();
    /** Contador local de ediciones (informativo; la concurrencia la decide la versión del servidor). */
    public int rev = 1;
    public long updatedAt;
    public String updatedBy = "";
    public boolean deleted;
    public long created;
    public String title = "", username = "", password = "", url = "", notes = "", category = "", totpSecret = "";
    public boolean favorite;
    public List<String> tags = new ArrayList<>();
    public List<Custom> custom = new ArrayList<>();
    public List<HistoryItem> history = new ArrayList<>();
    /** Versión remota en conflicto pendiente de resolver por el usuario; {@code null} si no hay conflicto. */
    public Record conflict;
    /** Id entero de la bóveda v2 de la que procede (solo informativo). */
    public Integer legacyId;
    public JsonObject extra = new JsonObject();

    public static Record create(String deviceId, long now) {
        Record r = new Record();
        r.created = now;
        r.updatedAt = now;
        r.updatedBy = deviceId;
        return r;
    }

    /** Marca la edición local: sube el contador y refresca autor y hora. */
    public void touch(String deviceId, long now) {
        rev++;
        updatedAt = now;
        updatedBy = deviceId;
    }

    /** Convierte en lápida: vacía el contenido. */
    public void markDeleted(String deviceId, long now) {
        deleted = true;
        title = username = password = url = notes = category = totpSecret = "";
        favorite = false;
        tags = new ArrayList<>();
        custom = new ArrayList<>();
        history = new ArrayList<>();
        conflict = null;
        touch(deviceId, now);
    }

    public Record copy() {
        return fromJson(toJson());
    }

    /** Campos de contenido comparables campo a campo (para la fusión). El historial se trata aparte. */
    public Map<String, Object> contentFields() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title); m.put("username", username); m.put("password", password);
        m.put("url", url); m.put("notes", notes); m.put("category", category);
        m.put("favorite", favorite); m.put("totpSecret", totpSecret);
        m.put("tags", List.copyOf(tags)); m.put("custom", List.copyOf(custom));
        return m;
    }

    @SuppressWarnings("unchecked")
    public void setContentField(String name, Object v) {
        switch (name) {
            case "title" -> title = (String) v;
            case "username" -> username = (String) v;
            case "password" -> password = (String) v;
            case "url" -> url = (String) v;
            case "notes" -> notes = (String) v;
            case "category" -> category = (String) v;
            case "favorite" -> favorite = (Boolean) v;
            case "totpSecret" -> totpSecret = (String) v;
            case "tags" -> tags = new ArrayList<>((List<String>) v);
            case "custom" -> custom = new ArrayList<>((List<Custom>) v);
            default -> throw new IllegalArgumentException(name);
        }
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, JsonElement> e : extra.entrySet()) o.add(e.getKey(), e.getValue());
        o.addProperty("uuid", uuid);
        o.addProperty("rev", rev);
        o.addProperty("updatedAt", updatedAt);
        o.addProperty("updatedBy", updatedBy);
        o.addProperty("deleted", deleted);
        o.addProperty("created", created);
        o.addProperty("title", title);
        o.addProperty("username", username);
        o.addProperty("password", password);
        o.addProperty("url", url);
        o.addProperty("notes", notes);
        o.addProperty("category", category);
        o.addProperty("favorite", favorite);
        o.addProperty("totpSecret", totpSecret);
        JsonArray t = new JsonArray();
        tags.forEach(t::add);
        o.add("tags", t);
        JsonArray c = new JsonArray();
        for (Custom f : custom) {
            JsonObject x = new JsonObject();
            x.addProperty("name", f.name());
            x.addProperty("value", f.value());
            x.addProperty("secret", f.secret());
            c.add(x);
        }
        o.add("custom", c);
        JsonArray h = new JsonArray();
        for (HistoryItem i : history) {
            JsonObject x = new JsonObject();
            x.addProperty("password", i.password());
            x.addProperty("replacedAt", i.replacedAt());
            h.add(x);
        }
        o.add("history", h);
        if (conflict != null) o.add("conflict", conflict.toJson());
        if (legacyId != null) o.addProperty("legacyId", legacyId);
        return o;
    }

    public static Record fromJson(JsonObject o) {
        Record r = new Record();
        r.uuid = str(o, "uuid", r.uuid);
        r.rev = o.has("rev") ? o.get("rev").getAsInt() : 1;
        r.updatedAt = o.has("updatedAt") ? o.get("updatedAt").getAsLong() : 0;
        r.updatedBy = str(o, "updatedBy", "");
        r.deleted = o.has("deleted") && o.get("deleted").getAsBoolean();
        r.created = o.has("created") ? o.get("created").getAsLong() : 0;
        r.title = str(o, "title", ""); r.username = str(o, "username", ""); r.password = str(o, "password", "");
        r.url = str(o, "url", ""); r.notes = str(o, "notes", ""); r.category = str(o, "category", "");
        r.totpSecret = str(o, "totpSecret", "");
        r.favorite = o.has("favorite") && o.get("favorite").getAsBoolean();
        if (o.has("tags")) o.getAsJsonArray("tags").forEach(e -> r.tags.add(e.getAsString()));
        if (o.has("custom")) o.getAsJsonArray("custom").forEach(e -> {
            JsonObject x = e.getAsJsonObject();
            r.custom.add(new Custom(str(x, "name", ""), str(x, "value", ""), x.has("secret") && x.get("secret").getAsBoolean()));
        });
        if (o.has("history")) o.getAsJsonArray("history").forEach(e -> {
            JsonObject x = e.getAsJsonObject();
            r.history.add(new HistoryItem(str(x, "password", ""), x.has("replacedAt") ? x.get("replacedAt").getAsLong() : 0));
        });
        if (o.has("conflict")) r.conflict = fromJson(o.getAsJsonObject("conflict"));
        if (o.has("legacyId")) r.legacyId = o.get("legacyId").getAsInt();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) if (!KNOWN.contains(e.getKey())) r.extra.add(e.getKey(), e.getValue());
        return r;
    }

    private static String str(JsonObject o, String k, String def) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? def : e.getAsString();
    }
}
