package dev.cipherkey.core.model;

import com.google.gson.JsonObject;
import dev.cipherkey.core.sync.DeviceKey;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Identidad y vínculos de ESTE dispositivo para la sincronización directa (proximidad). Vive dentro de la bóveda cifrada y
 * NO depende de ningún servidor ni grupo de sincronización: la confianza entre dos dispositivos nace de comparar el código
 * de seguridad (SAS) y fijar la clave pública del otro (ver docs/adr/0002-proximidad-sin-servidor.md).
 * No se copia a otro dispositivo: {@link VaultDocument#withoutSync()} lo elimina al exportar.
 */
public final class LinkState {
    public String deviceId = "";
    public String deviceName = "";
    /** Clave privada Ed25519 del dispositivo (32 bytes, base64). */
    public String devicePrivateKey = "";
    /** deviceId del par -> estado. Solo hay entradas de dispositivos cuyo código de seguridad se confirmó. */
    public Map<String, SyncState.Peer> peers = new LinkedHashMap<>();

    /**
     * Devuelve la identidad de proximidad de la bóveda, creándola si no existe. Si la bóveda ya sincroniza con un servidor
     * se reutiliza esa identidad (mismo id y misma clave); si no, se genera una nueva, sin tocar red alguna.
     */
    public static LinkState ensure(VaultDocument d, String defaultName) {
        if (d.link == null) {
            LinkState l = new LinkState();
            if (d.sync != null && !d.sync.deviceId.isEmpty() && !d.sync.devicePrivateKey.isEmpty()) {
                l.deviceId = d.sync.deviceId;
                l.deviceName = d.sync.deviceName;
                l.devicePrivateKey = d.sync.devicePrivateKey;
            } else {
                l.deviceId = UUID.randomUUID().toString();
                l.deviceName = defaultName;
                l.devicePrivateKey = DeviceKey.generate().privateBase64();
            }
            if (l.deviceName == null || l.deviceName.isBlank()) l.deviceName = defaultName;
            d.link = l;
        }
        return d.link;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("deviceId", deviceId);
        o.addProperty("deviceName", deviceName);
        o.addProperty("devicePrivateKey", devicePrivateKey);
        JsonObject ps = new JsonObject();
        for (var e : peers.entrySet()) {
            JsonObject x = new JsonObject();
            x.addProperty("name", e.getValue().name);
            x.addProperty("publicKey", e.getValue().publicKey);
            x.addProperty("lastSync", e.getValue().lastSync);
            JsonObject bb = new JsonObject();
            for (var be : e.getValue().base.entrySet()) bb.add(be.getKey(), be.getValue().toJson());
            x.add("base", bb);
            ps.add(e.getKey(), x);
        }
        o.add("peers", ps);
        return o;
    }

    public static LinkState fromJson(JsonObject o) {
        LinkState l = new LinkState();
        l.deviceId = o.get("deviceId").getAsString();
        l.deviceName = o.get("deviceName").getAsString();
        l.devicePrivateKey = o.get("devicePrivateKey").getAsString();
        for (var e : o.getAsJsonObject("peers").entrySet()) {
            JsonObject x = e.getValue().getAsJsonObject();
            SyncState.Peer p = new SyncState.Peer();
            p.name = x.get("name").getAsString();
            p.publicKey = x.get("publicKey").getAsString();
            p.lastSync = x.get("lastSync").getAsLong();
            for (var b : x.getAsJsonObject("base").entrySet()) p.base.put(b.getKey(), Record.fromJson(b.getValue().getAsJsonObject()));
            l.peers.put(e.getKey(), p);
        }
        return l;
    }
}
