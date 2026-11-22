package dev.cipherkey.core;

import dev.cipherkey.core.model.Record;
import dev.cipherkey.core.model.SyncState;
import dev.cipherkey.core.model.VaultDocument;
import dev.cipherkey.core.p2p.*;
import dev.cipherkey.core.sync.*;
import org.junit.jupiter.api.*;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proximidad y servidor juntos con el binario real del servidor Rust: dos dispositivos emparejados por el servidor se
 * sincronizan directamente y luego cada uno sube lo suyo sin duplicados ni conflictos falsos. Se omite sin binario.
 */
class PeerServerE2ETest {
    static Process proc;
    static String base;

    @BeforeAll static void start() throws Exception {
        String bin = System.getenv().getOrDefault("CK_SERVER_BIN",
                System.getProperty("user.home") + "/Proyectos/cipherkey-mobile/server/target/debug/cipherkey-server");
        assumeTrue(new File(bin).canExecute(), "binario del servidor no disponible: " + bin);
        int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        Path dir = Files.createTempDirectory("ck-p2p-e2e");
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

    @AfterAll static void stop() { if (proc != null) proc.destroy(); }

    static final class Dev implements Locked {
        final VaultDocument doc = new VaultDocument();
        final DeviceKey key = DeviceKey.generate();
        ServerClient client;
        PeerService peers;
        @Override public synchronized <T> T run(Work<T> w) throws Exception { return w.apply(doc); }
        void sync() throws Exception { synchronized (this) { new SyncEngine(doc, client, System::currentTimeMillis).sync(); } }
    }

    @Test void proximidadYServidorConvergenSinConflictosFalsos() throws Exception {
        Dev a = new Dev();
        a.doc.sync = new SyncState();
        a.doc.sync.serverUrl = base; a.doc.sync.deviceName = "escritorio"; a.doc.sync.devicePrivateKey = a.key.privateBase64();
        a.doc.sync.deviceId = ServerClient.createVault(base, null, a.doc.vaultId, a.key, "escritorio");
        a.client = new ServerClient(base, a.doc.sync.deviceId, a.key);
        Dev b = new Dev();
        PairingCode code = new PairingCode(base, a.doc.vaultId, a.doc.syncKey, a.client.newEnrollment().token());
        b.doc.vaultId = code.vaultId(); b.doc.syncKey = code.syncKey();
        b.doc.sync = new SyncState();
        b.doc.sync.serverUrl = base; b.doc.sync.deviceName = "móvil"; b.doc.sync.devicePrivateKey = b.key.privateBase64();
        b.doc.sync.deviceId = ServerClient.enroll(base, code.vaultId(), code.token(), b.key, "móvil");
        b.client = new ServerClient(base, b.doc.sync.deviceId, b.key);

        Record r = Record.create(a.doc.sync.deviceId, 1000);
        r.title = "Correo"; r.username = "ana"; r.password = "uno";
        a.doc.records.add(r);
        SyncEngine.markDirty(a.doc, r);
        a.sync(); b.sync();
        assertEquals("uno", b.doc.find(r.uuid).orElseThrow().password);

        a.peers = new PeerService(a, 0, false, (n, s, i) -> true, new PeerService.Listener() {}, System::currentTimeMillis);
        b.peers = new PeerService(b, 0, false, (n, s, i) -> true, new PeerService.Listener() {}, System::currentTimeMillis);
        PeerSync.MergeGate ok = new PeerSync.MergeGate() {
            @Override public boolean approve(PeerSync.Summary x) { return true; }
            @Override public void backup() { }
        };
        a.peers.setMergeGate(ok); b.peers.setMergeGate(ok);
        a.peers.start(); b.peers.start();
        try {
            b.peers.openPairingWindow(60_000);
            a.peers.syncWith(new InetSocketAddress("127.0.0.1", b.peers.port()), true);

            // A edita y se sincroniza SOLO por proximidad (el servidor aún no sabe nada)
            Record ra = a.doc.find(r.uuid).orElseThrow();
            ra.password = "dos"; ra.touch(a.doc.sync.deviceId, 2000);
            SyncEngine.markDirty(a.doc, ra);
            a.peers.syncWith(new InetSocketAddress("127.0.0.1", b.peers.port()), false);
            assertEquals("dos", b.doc.find(r.uuid).orElseThrow().password);

            // luego ambos hablan con el servidor: no deben aparecer conflictos aunque los dos tengan el cambio
            a.sync(); b.sync(); a.sync();
            for (Dev d : new Dev[]{a, b}) {
                Record x = d.doc.find(r.uuid).orElseThrow();
                assertEquals("dos", x.password);
                assertNull(x.conflict, "el mismo cambio llegado por dos vías no es un conflicto");
            }
            assertEquals(1, a.doc.records.size());
            assertEquals(1, b.doc.records.size());
        } finally {
            a.peers.close(); b.peers.close();
        }
    }
}
