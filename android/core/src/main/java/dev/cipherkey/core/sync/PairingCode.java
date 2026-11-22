package dev.cipherkey.core.sync;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Código de emparejamiento que muestra un dispositivo (texto o QR) y escanea el otro. Contiene la clave de sincronización:
 * es un secreto, solo debe mostrarse en pantalla durante el emparejamiento y no guardarse.
 */
public record PairingCode(String server, String vaultId, String syncKey, String token) {
    private static final String PREFIX = "ckpair1:";

    public String encode() {
        JsonObject o = new JsonObject();
        o.addProperty("s", server); o.addProperty("v", vaultId); o.addProperty("k", syncKey); o.addProperty("t", token);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(o.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static PairingCode decode(String text) {
        String t = text.trim();
        if (!t.startsWith(PREFIX)) throw new IllegalArgumentException("No es un código de emparejamiento de CipherKey");
        JsonObject o = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(t.substring(PREFIX.length())),
                StandardCharsets.UTF_8)).getAsJsonObject();
        return new PairingCode(o.get("s").getAsString(), o.get("v").getAsString(), o.get("k").getAsString(), o.get("t").getAsString());
    }
}
