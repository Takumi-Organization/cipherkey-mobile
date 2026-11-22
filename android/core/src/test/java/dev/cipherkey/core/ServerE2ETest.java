package dev.cipherkey.core;

import dev.cipherkey.core.merge.Merger;
import dev.cipherkey.core.model.Record;
import dev.cipherkey.core.model.VaultDocument;
import dev.cipherkey.core.sync.*;
import org.junit.jupiter.api.*;

import java.io.File;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pruebas extremo a extremo: cliente Java real (firmas Ed25519 de BouncyCastle, cifrado AES-GCM) contra el binario real del
 * servidor Rust. Se omiten si el binario no existe (variable CK_SERVER_BIN o ~/Proyectos/cipherkey-mobile/server/target/...).
 */
class ServerE2ETest {
    static Process proc;
    static String base;
    static Path dir;

    @BeforeAll
    static void startServer() throws Exception {
        String bin = System.getenv().getOrDefault("CK_SERVER_BIN",
                System.getProperty("user.home") + "/Proyectos/cipherkey-mobile/server/target/debug/cipherkey-server");
        assumeTrue(new File(bin).canExecute(), "binario del servidor no disponible: " + bin);
        int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        dir = Files.createTempDirectory("ck-e2e");
        ProcessBuilder pb = new ProcessBuilder(bin);
        pb.environment().put("CK_BIND", "127.0.0.1:" + port);
        pb.environment().put("CK_DB", dir.resolve("s.db").toString());
        pb.environment().put("CK_OPEN_REGISTRATION", "1");
        pb.environment().put("CK_ANON_PER_MIN", "1000");
        pb.redirectErrorStream(true).redirectOutput(dir.resolve("server.log").toFile());
        proc = pb.start();
        base = "http://127.0.0.1:" + port;
        for (int i = 0; i < 100; i++) {
            try { new java.net.URL(base + "/v1/health").openStream().close(); return; } catch (Exception e) { Thread.sleep(50); }
        }
        fail("el servidor no arrancó");
    }

    @AfterAll
    static void stop() { if (proc != null) proc.destroy(); }

    static class Dev {
        final VaultDocument doc;
        final DeviceKey key = DeviceKey.generate();
        ServerClient client;
        Dev(VaultDocument d) { doc = d; }
    }

    Dev desktop() throws Exception {
        VaultDocument d = new VaultDocument();
        d.sync = new dev.cipherkey.core.model.SyncState();
        Dev dev = new Dev(d);
        d.sync.serverUrl = base; d.sync.deviceName = "desktop"; d.sync.devicePrivateKey = dev.key.privateBase64();
        d.sync.deviceId = ServerClient.createVault(base, null, d.vaultId, dev.key, "desktop");
        dev.client = new ServerClient(base, d.sync.deviceId, dev.key);
        return dev;
    }

    /** Empareja un segundo dispositivo con el código que mostraría el primero. */
    Dev pair(Dev first, String name) throws Exception {
        PairingCode code = PairingCode.decode(new PairingCode(base, first.doc.vaultId, first.doc.syncKey,
                first.client.newEnrollment().token()).encode());
        VaultDocument d = new VaultDocument();
        d.vaultId = code.vaultId(); d.syncKey = code.syncKey();
        d.sync = new dev.cipherkey.core.model.SyncState();
        Dev dev = new Dev(d);
        d.sync.serverUrl = code.server(); d.sync.deviceName = name; d.sync.devicePrivateKey = dev.key.privateBase64();
        d.sync.deviceId = ServerClient.enroll(code.server(), code.vaultId(), code.token(), dev.key, name);
        dev.client = new ServerClient(base, d.sync.deviceId, dev.key);
        return dev;
    }

    static SyncEngine.Summary sync(Dev d) throws Exception {
        return new SyncEngine(d.doc, d.client, System::currentTimeMillis).sync();
    }

    static Record add(Dev d, String title, String pwd) {
        Record r = Record.create(d.doc.sync.deviceId, System.currentTimeMillis());
        r.title = title; r.password = pwd;
        d.doc.records.add(r);
        SyncEngine.markDirty(d.doc, r);
        return r;
    }

    static void edit(Dev d, Record r, java.util.function.Consumer<Record> f) {
        f.accept(r);
        r.touch(d.doc.sync.deviceId, System.currentTimeMillis());
        SyncEngine.markDirty(d.doc, r);
    }

    @Test
    void scenariosABC_overRealServer() throws Exception {
        Dev pc = desktop();
        Dev phone = pair(pc, "movil");

        // A: crear en escritorio -> aparece en el móvil
        Record r = add(pc, "GitHub", "p1");
        sync(pc); sync(phone);
        assertEquals("p1", phone.doc.live().get(0).password);

        // B: crear en el móvil -> aparece en el escritorio
        add(phone, "Banco", "p2");
        sync(phone); sync(pc);
        assertEquals(2, pc.doc.live().size());

        // C: móvil sin conexión; el escritorio edita; al volver, el móvil recibe
        ServerClient offline = new ServerClient("http://127.0.0.1:1", phone.doc.sync.deviceId, phone.key);
        edit(pc, r, x -> x.password = "p1-v2");
        sync(pc);
        assertThrows(java.io.IOException.class, () -> new SyncEngine(phone.doc, offline, System::currentTimeMillis).sync());
        assertEquals("p1", phone.doc.records.stream().filter(x -> x.uuid.equals(r.uuid)).findFirst().get().password);
        sync(phone);
        assertEquals("p1-v2", phone.doc.records.stream().filter(x -> x.uuid.equals(r.uuid)).findFirst().get().password);
    }

    @Test
    void concurrentEditsConflictAndResolveOverRealServer() throws Exception {
        Dev pc = desktop();
        Dev phone = pair(pc, "movil");
        Record r = add(pc, "Svc", "base");
        sync(pc); sync(phone);
        edit(pc, r, x -> x.password = "pc");
        edit(phone, phone.doc.live().get(0), x -> x.password = "movil");
        sync(pc);
        var s = sync(phone);
        assertEquals(1, s.conflicts());
        Record c = phone.doc.live().get(0);
        assertEquals("pc", c.conflict.password);
        Record resolved = Merger.resolve(c, false, phone.doc.sync.deviceId, System.currentTimeMillis()); // me quedo con la mía
        phone.doc.records.set(0, resolved);
        SyncEngine.markDirty(phone.doc, resolved);
        sync(phone); sync(pc);
        assertEquals("movil", pc.doc.live().get(0).password);
        assertNull(pc.doc.live().get(0).conflict);
    }

    @Test
    void revokedDeviceIsLockedOutAndPairingTokenIsSingleUse() throws Exception {
        Dev pc = desktop();
        PairingCode code = new PairingCode(base, pc.doc.vaultId, pc.doc.syncKey, pc.client.newEnrollment().token());
        DeviceKey k = DeviceKey.generate();
        String id = ServerClient.enroll(base, code.vaultId(), code.token(), k, "movil");
        assertThrows(ServerClient.ServerException.class, () -> ServerClient.enroll(base, code.vaultId(), code.token(), DeviceKey.generate(), "clon"));

        ServerClient phone = new ServerClient(base, id, k);
        assertEquals(0, phone.pull(0, 10).changes().size());
        List<ServerClient.DeviceInfo> devs = pc.client.devices();
        assertEquals(2, devs.size());
        pc.client.revoke(id);
        var ex = assertThrows(ServerClient.ServerException.class, () -> phone.pull(0, 10));
        assertEquals(403, ex.status);
        assertEquals(0, pc.client.pull(0, 10).changes().size());
    }

    @Test
    void serverNeverHoldsPlaintext() throws Exception {
        Dev pc = desktop();
        add(pc, "TituloMuySecreto", "contraseña-ultra-secreta-123");
        sync(pc);
        byte[] db = Files.readAllBytes(dir.resolve("s.db"));
        byte[] wal = Files.exists(dir.resolve("s.db-wal")) ? Files.readAllBytes(dir.resolve("s.db-wal")) : new byte[0];
        String all = new String(db, java.nio.charset.StandardCharsets.ISO_8859_1) + new String(wal, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(all.contains("TituloMuySecreto"));
        assertFalse(all.contains("ultra-secreta"));
        assertFalse(all.contains(pc.doc.syncKey));
    }
}
