package dev.cipherkey.core;

import dev.cipherkey.core.crypto.VaultCrypto;
import dev.cipherkey.core.crypto.VaultCrypto.KdfParams;
import dev.cipherkey.core.format.VaultContainer;
import dev.cipherkey.core.model.*;
import dev.cipherkey.core.model.Record;
import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class CryptoAndFormatTest {
    static final KdfParams FAST = new KdfParams(8 * 1024, 1, 1);

    @Test
    void argon2idRfc9106Vector() {
        // Vector de RFC 9106 §5.3 (Argon2id): comprueba que la implementación coincide con el estándar.
        byte[] pwd = new byte[32]; Arrays.fill(pwd, (byte) 1);
        var p = new org.bouncycastle.crypto.params.Argon2Parameters.Builder(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_id)
                .withVersion(0x13).withSalt(rep(2, 16)).withSecret(rep(3, 8)).withAdditional(rep(4, 12))
                .withIterations(3).withMemoryAsKB(32).withParallelism(4).build();
        var g = new org.bouncycastle.crypto.generators.Argon2BytesGenerator();
        g.init(p);
        byte[] out = new byte[32];
        g.generateBytes(pwd, out);
        assertEquals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659", hex(out));
    }

    @Test
    void gcmRoundTripAndAadBinding() throws Exception {
        byte[] key = VaultCrypto.randomBytes(32);
        byte[] ct = VaultCrypto.encrypt(key, "a".getBytes(), "secreto".getBytes());
        assertArrayEquals("secreto".getBytes(), VaultCrypto.decrypt(key, "a".getBytes(), ct));
        assertThrows(AEADBadTagException.class, () -> VaultCrypto.decrypt(key, "b".getBytes(), ct));
        ct[ct.length - 1] ^= 1;
        assertThrows(AEADBadTagException.class, () -> VaultCrypto.decrypt(key, "a".getBytes(), ct));
    }

    @Test
    void noncesAreNeverReused() throws Exception {
        byte[] key = VaultCrypto.randomBytes(32);
        var seen = new java.util.HashSet<String>();
        for (int i = 0; i < 2000; i++) seen.add(hex(Arrays.copyOf(VaultCrypto.encrypt(key, new byte[0], new byte[1]), 12)));
        assertEquals(2000, seen.size());
    }

    @Test
    void containerV3RoundTripWrongPasswordAndTamper() throws Exception {
        byte[] header = VaultContainer.newHeader(3, FAST, VaultCrypto.randomBytes(32));
        byte[] key = VaultCrypto.deriveKey("clave-larga-123".toCharArray(), VaultContainer.saltOf(header), FAST);
        byte[] file = VaultContainer.seal(header, key, "hola".getBytes());
        var opened = VaultContainer.open(file, "clave-larga-123".toCharArray());
        assertEquals(3, opened.version());
        assertArrayEquals("hola".getBytes(), opened.plaintext());
        assertThrows(VaultContainer.WrongPasswordException.class, () -> VaultContainer.open(file, "otra".toCharArray()));
        byte[] tampered = file.clone();
        tampered[10] ^= 1; // memoria KDF dentro de la cabecera
        assertThrows(Exception.class, () -> VaultContainer.open(tampered, "clave-larga-123".toCharArray()));
        byte[] bad = file.clone();
        bad[0] = 'X';
        assertThrows(VaultContainer.FormatException.class, () -> VaultContainer.open(bad, "x".toCharArray()));
    }

    @Test
    void rejectsAbusiveKdfParams() {
        byte[] header = VaultContainer.newHeader(3, new KdfParams(8 * 1024 * 1024, 3, 1), new byte[32]);
        byte[] file = Arrays.copyOf(header, header.length + 64);
        assertThrows(VaultContainer.FormatException.class, () -> VaultContainer.open(file, "x".toCharArray()));
    }

    @Test
    void documentPreservesUnknownFields() {
        String json = "{\"schema\":3,\"vaultId\":\"v\",\"syncKey\":\"k\",\"futuro\":{\"a\":1},\"records\":["
                + "{\"uuid\":\"u1\",\"title\":\"T\",\"campoNuevo\":[1,2]}]}";
        VaultDocument d = VaultDocument.fromBytes(json.getBytes());
        VaultDocument again = VaultDocument.fromBytes(d.toBytes());
        assertTrue(again.extra.has("futuro"));
        assertTrue(again.records.get(0).extra.has("campoNuevo"));
        assertThrows(IllegalArgumentException.class, () -> VaultDocument.fromBytes("{\"schema\":99,\"vaultId\":\"v\",\"syncKey\":\"k\",\"records\":[]}".getBytes()));
    }

    @Test
    void exportWithoutSyncDropsDeviceIdentity() {
        VaultDocument d = Fakes.device(null, null, "dev-1");
        d.sync.devicePrivateKey = "SECRETO";
        d.records.add(Record.create("dev-1", 1));
        VaultDocument ex = d.withoutSync();
        assertNull(ex.sync);
        assertEquals(1, ex.records.size());
        assertFalse(new String(ex.toBytes()).contains("SECRETO"));
    }

    @Test
    void v2MigrationKeepsDataAndIsReversible() {
        String v2 = "[{\"id\":7,\"title\":\"GitHub\",\"username\":\"adri\",\"password\":\"pw\",\"url\":\"https://github.com\","
                + "\"notes\":\"n\",\"category\":\"Dev\",\"favorite\":true,\"totpSecret\":\"ABC\","
                + "\"history\":[{\"password\":\"old\",\"replacedAt\":\"2026-01-02T03:04:05\"}],"
                + "\"created\":\"2026-01-01T10:00:00\",\"modified\":\"2026-02-01T10:00:00\"}]";
        var zone = java.time.ZoneId.of("Europe/Madrid");
        VaultDocument d = V2Migrator.migrate(v2.getBytes(), zone, "dev-1");
        Record r = d.records.get(0);
        assertEquals("GitHub", r.title);
        assertEquals(7, r.legacyId);
        assertTrue(r.favorite);
        assertEquals("old", r.history.get(0).password());
        assertEquals(java.time.LocalDateTime.parse("2026-01-01T10:00:00").atZone(zone).toInstant().toEpochMilli(), r.created);
        String back = new String(V2Migrator.toV2(d.records, zone));
        assertTrue(back.contains("\"id\":7") && back.contains("2026-02-01T10:00"));
    }

    static byte[] rep(int v, int n) { byte[] b = new byte[n]; Arrays.fill(b, (byte) v); return b; }
    static String hex(byte[] b) { return dev.cipherkey.core.sync.DeviceKey.hex(b); }
}
