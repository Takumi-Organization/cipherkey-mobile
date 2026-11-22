package dev.cipherkey.core.model;

import com.google.gson.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Estado de sincronización de ESTE dispositivo. Vive dentro de la bóveda cifrada. No debe copiarse a otro dispositivo:
 * {@link VaultDocument#withoutSync()} lo elimina al exportar.
 */
public final class SyncState {
    /** Última versión conocida en el servidor de un registro y su contenido en ese momento (base de la fusión). */
    public record Baseline(long ver, Record base) {}

    public String serverUrl = "";
    public String deviceId = "";
    public String deviceName = "";
    /** Clave privada Ed25519 del dispositivo (32 bytes, base64). */
    public String devicePrivateKey = "";
    public long lastSeq;
    public Map<String, Baseline> baselines = new LinkedHashMap<>();
    /** uuid -> opId de la operación pendiente de enviar; el orden de inserción se conserva. */
    public Map<String, String> pending = new LinkedHashMap<>();

    /** Dispositivo vinculado para sincronizar en proximidad (ver {@link LinkState}): clave pública fijada y base de fusión. */
    public static final class Peer {
        public String name = "";
        /** Clave pública Ed25519 (base64) fijada tras confirmar el código de seguridad. */
        public String publicKey = "";
        public long lastSync;
        /** Último contenido de cada registro tal como lo tenía este par (base de la fusión a tres bandas). */
        public Map<String, Record> base = new LinkedHashMap<>();
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("serverUrl", serverUrl);
        o.addProperty("deviceId", deviceId);
        o.addProperty("deviceName", deviceName);
        o.addProperty("devicePrivateKey", devicePrivateKey);
        o.addProperty("lastSeq", lastSeq);
        JsonObject b = new JsonObject();
        for (var e : baselines.entrySet()) {
            JsonObject x = new JsonObject();
            x.addProperty("ver", e.getValue().ver());
            if (e.getValue().base() != null) x.add("base", e.getValue().base().toJson());
            b.add(e.getKey(), x);
        }
        o.add("baselines", b);
        JsonObject p = new JsonObject();
        pending.forEach(p::addProperty);
        o.add("pending", p);
        return o;
    }

    public static SyncState fromJson(JsonObject o) {
        SyncState s = new SyncState();
        s.serverUrl = o.get("serverUrl").getAsString();
        s.deviceId = o.get("deviceId").getAsString();
        s.deviceName = o.get("deviceName").getAsString();
        s.devicePrivateKey = o.get("devicePrivateKey").getAsString();
        s.lastSeq = o.get("lastSeq").getAsLong();
        for (var e : o.getAsJsonObject("baselines").entrySet()) {
            JsonObject x = e.getValue().getAsJsonObject();
            s.baselines.put(e.getKey(), new Baseline(x.get("ver").getAsLong(),
                    x.has("base") ? Record.fromJson(x.getAsJsonObject("base")) : null));
        }
        for (var e : o.getAsJsonObject("pending").entrySet()) s.pending.put(e.getKey(), e.getValue().getAsString());
        return s;
    }
}
