package dev.cipherkey.core.model;

import com.google.gson.*;
import dev.cipherkey.core.crypto.VaultCrypto;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Contenido descifrado de una bóveda v3. */
public final class VaultDocument {
    public static final int SCHEMA = 3;

    public String vaultId = UUID.randomUUID().toString();
    /** Clave aleatoria de 32 bytes (base64) con la que se cifran los registros que viajan al servidor. */
    public String syncKey = Base64.getEncoder().encodeToString(VaultCrypto.randomBytes(32));
    public List<Record> records = new ArrayList<>();
    public SyncState sync;
    /** Identidad y vínculos de proximidad de este dispositivo (independiente del servidor). */
    public LinkState link;
    public JsonObject extra = new JsonObject();

    public Optional<Record> find(String uuid) {
        return records.stream().filter(r -> r.uuid.equals(uuid)).findFirst();
    }

    /** Registros visibles (sin lápidas). */
    public List<Record> live() {
        return records.stream().filter(r -> !r.deleted).toList();
    }

    /** Copia sin estado de sincronización, apta para exportar o copiar a otro dispositivo. */
    public VaultDocument withoutSync() {
        VaultDocument d = fromBytes(toBytes());
        d.sync = null;
        d.link = null;
        return d;
    }

    public byte[] toBytes() {
        JsonObject o = new JsonObject();
        for (var e : extra.entrySet()) o.add(e.getKey(), e.getValue());
        o.addProperty("schema", SCHEMA);
        o.addProperty("vaultId", vaultId);
        o.addProperty("syncKey", syncKey);
        JsonArray a = new JsonArray();
        records.forEach(r -> a.add(r.toJson()));
        o.add("records", a);
        if (sync != null) o.add("sync", sync.toJson());
        if (link != null) o.add("link", link.toJson());
        return o.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static VaultDocument fromBytes(byte[] bytes) {
        JsonObject o = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        int schema = o.get("schema").getAsInt();
        if (schema > SCHEMA) throw new IllegalArgumentException("Esquema de bóveda más nuevo que esta versión: " + schema);
        VaultDocument d = new VaultDocument();
        d.vaultId = o.get("vaultId").getAsString();
        d.syncKey = o.get("syncKey").getAsString();
        d.records = new ArrayList<>();
        o.getAsJsonArray("records").forEach(e -> d.records.add(Record.fromJson(e.getAsJsonObject())));
        if (o.has("sync")) d.sync = SyncState.fromJson(o.getAsJsonObject("sync"));
        if (o.has("link")) d.link = LinkState.fromJson(o.getAsJsonObject("link"));
        for (var e : o.entrySet())
            if (!Set.of("schema", "vaultId", "syncKey", "records", "sync", "link").contains(e.getKey())) d.extra.add(e.getKey(), e.getValue());
        return d;
    }
}
