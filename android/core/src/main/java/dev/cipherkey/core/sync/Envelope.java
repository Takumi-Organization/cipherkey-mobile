package dev.cipherkey.core.sync;

import com.google.gson.JsonParser;
import dev.cipherkey.core.crypto.VaultCrypto;
import dev.cipherkey.core.model.Record;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/** Cifrado de un registro para el servidor. AAD = vaultId + uuid: un blob no se puede mover a otro registro ni bóveda. */
public final class Envelope {
    private Envelope() {}

    public static byte[] seal(byte[] syncKey, String vaultId, Record r) throws GeneralSecurityException {
        Record clean = r.copy();
        clean.conflict = null;
        return VaultCrypto.encrypt(syncKey, aad(vaultId, r.uuid), clean.toJson().toString().getBytes(StandardCharsets.UTF_8));
    }

    public static Record open(byte[] syncKey, String vaultId, String uuid, byte[] blob) throws GeneralSecurityException {
        byte[] plain = VaultCrypto.decrypt(syncKey, aad(vaultId, uuid), blob);
        Record r = Record.fromJson(JsonParser.parseString(new String(plain, StandardCharsets.UTF_8)).getAsJsonObject());
        if (!r.uuid.equals(uuid)) throw new GeneralSecurityException("uuid del registro no coincide con el del sobre");
        return r;
    }

    private static byte[] aad(String vaultId, String uuid) {
        return ("ck-rec|" + vaultId + "|" + uuid).getBytes(StandardCharsets.UTF_8);
    }
}
