package dev.cipherkey.core;

import dev.cipherkey.core.model.LinkState;
import dev.cipherkey.core.model.Record;
import dev.cipherkey.core.model.SyncState;
import dev.cipherkey.core.model.VaultDocument;
import dev.cipherkey.core.p2p.Locked;
import dev.cipherkey.core.p2p.PeerService;
import dev.cipherkey.core.p2p.PeerSync;
import dev.cipherkey.core.sync.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dispositivo de escritorio SIMULADO para verificar a mano el emparejado y la sincronización con la app Android del
 * emulador (no es una prueba JUnit). Funciona SIN servidor (bóveda propia en memoria, identidad de proximidad propia).
 * Lee órdenes de la entrada estándar: {@code listen <puerto>}, {@code allow}, {@code pair <ip:puerto>},
 * {@code sync <ip:puerto>}, {@code add <título> <contraseña>}, {@code edit <título> <contraseña>}, {@code dump},
 * {@code unlink}, {@code quit}; y, para el caso con servidor, {@code pairserver <código>} y {@code serversync}.
 * El código de seguridad se muestra y se acepta o rechaza creando {@code <dir>/sas.ok} o {@code <dir>/sas.no}; el resumen de
 * la primera fusión, igual con {@code <dir>/merge.ok} o {@code <dir>/merge.no}.
 */
public final class PeerHarness implements Locked {
    final VaultDocument doc = new VaultDocument();
    final DeviceKey key = DeviceKey.generate();
    ServerClient client;
    PeerService peers;
    final Path dir;

    PeerHarness(Path dir) { this.dir = dir; }

    @Override public synchronized <T> T run(Work<T> w) throws Exception { return w.apply(doc); }

    boolean waitFile(String prefix, String what) throws Exception {
        Path ok = dir.resolve(prefix + ".ok"), no = dir.resolve(prefix + ".no");
        Files.deleteIfExists(ok); Files.deleteIfExists(no);
        System.out.println(what);
        System.out.flush();
        for (int i = 0; i < 1200; i++) {
            if (Files.exists(ok)) { Files.deleteIfExists(ok); return true; }
            if (Files.exists(no)) { Files.deleteIfExists(no); return false; }
            Thread.sleep(100);
        }
        return false;
    }

    final PeerSync.MergeGate gate = new PeerSync.MergeGate() {
        @Override public boolean approve(PeerSync.Summary s) throws Exception {
            return waitFile("merge", "MERGE peer=" + s.peerName() + " mineOnly=" + s.mineOnly() + " theirsOnly=" + s.theirsOnly() + " differing=" + s.differing());
        }
        @Override public void backup() { System.out.println("BACKUP (simulado: el arnés vive en memoria)"); }
    };

    boolean askSas(String peer, String sas, boolean incoming) throws Exception {
        Path ok = dir.resolve("sas.ok"), no = dir.resolve("sas.no");
        Files.deleteIfExists(ok); Files.deleteIfExists(no);
        System.out.println("SAS " + sas + " peer=" + peer + " incoming=" + incoming);
        for (int i = 0; i < 1200; i++) {
            if (Files.exists(ok)) { Files.deleteIfExists(ok); return true; }
            if (Files.exists(no)) { Files.deleteIfExists(no); return false; }
            Thread.sleep(100);
        }
        return false;
    }

    void dump() {
        synchronized (this) {
            System.out.println("RECORDS " + doc.records.size() + " peers=" + (doc.link == null ? "[]" : doc.link.peers.keySet()) + " pending=" + (doc.sync == null ? 0 : doc.sync.pending.size()));
            for (Record r : doc.records)
                System.out.println("  - " + r.title + " | " + r.username + " | " + r.password + (r.deleted ? " [borrado]" : "") + (r.conflict != null ? " [CONFLICTO]" : ""));
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "/tmp/ck-harness");
        Files.createDirectories(dir);
        PeerHarness h = new PeerHarness(dir);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = in.readLine()) != null) {
            String[] a = line.trim().split("\\s+", 3);
            try {
                switch (a[0]) {
                    case "pairserver" -> {
                        String[] ca = line.trim().split("\\s+");
                        PairingCode code = PairingCode.decode(ca[1]);
                        if (ca.length > 2) code = new PairingCode(ca[2], code.vaultId(), code.syncKey(), code.token()); // el servidor visto desde aquí
                        h.doc.vaultId = code.vaultId(); h.doc.syncKey = code.syncKey();
                        h.doc.sync = new SyncState();
                        h.doc.sync.serverUrl = code.server(); h.doc.sync.deviceName = "Escritorio (arnés)";
                        h.doc.sync.devicePrivateKey = h.key.privateBase64();
                        h.doc.sync.deviceId = ServerClient.enroll(code.server(), code.vaultId(), code.token(), h.key, "Escritorio (arnés)");
                        h.client = new ServerClient(code.server(), h.doc.sync.deviceId, h.key);
                        h.doc.link = null; // se reutiliza la identidad del servidor
                        System.out.println("OK pairserver id=" + h.doc.sync.deviceId);
                    }
                    case "serversync" -> {
                        SyncEngine.Summary s;
                        synchronized (h) { s = new SyncEngine(h.doc, h.client, System::currentTimeMillis).sync(); }
                        System.out.println("OK serversync " + s);
                    }
                    case "listen" -> {
                        h.peers = new PeerService(h, Integer.parseInt(a[1]), false, h::askSas, new PeerService.Listener() {
                            public void synced(PeerSync.Result r, boolean incoming) { System.out.println("EVENT synced " + r + " incoming=" + incoming); }
                            public void failed(String who, String m, boolean incoming) { System.out.println("EVENT failed " + who + " " + m + " incoming=" + incoming); }
                        }, System::currentTimeMillis);
                        h.peers.setDefaultDeviceName("Escritorio (arnés)");
                        h.peers.setMergeGate(h.gate);
                        h.peers.start();
                        h.peers.ensureIdentity();
                        System.out.println("OK listen port=" + h.peers.port());
                    }
                    case "allow" -> { h.peers.openPairingWindow(120_000); System.out.println("OK allow"); }
                    case "pair", "sync" -> {
                        String[] hp = a[1].split(":");
                        PeerSync.Result r = h.peers.syncWith(new InetSocketAddress(hp[0], Integer.parseInt(hp[1])), a[0].equals("pair"));
                        System.out.println("OK " + a[0] + " " + r);
                    }
                    case "add" -> {
                        synchronized (h) {
                            Record r = Record.create(LinkState.ensure(h.doc, "Escritorio (arnés)").deviceId, System.currentTimeMillis());
                            r.title = a[1]; r.username = "escritorio"; r.password = a.length > 2 ? a[2] : "x";
                            h.doc.records.add(r); SyncEngine.markDirty(h.doc, r);
                        }
                        System.out.println("OK add");
                    }
                    case "edit" -> {
                        synchronized (h) {
                            Record r = h.doc.records.stream().filter(x -> x.title.equals(a[1])).findFirst().orElseThrow();
                            r.password = a[2]; r.touch(h.doc.link.deviceId, System.currentTimeMillis()); SyncEngine.markDirty(h.doc, r);
                        }
                        System.out.println("OK edit");
                    }
                    case "unlink" -> { synchronized (h) { h.doc.link.peers.clear(); } System.out.println("OK unlink"); }
                    case "dump" -> h.dump();
                    case "quit" -> { if (h.peers != null) h.peers.close(); System.exit(0); }
                    default -> System.out.println("?? " + line);
                }
            } catch (Throwable t) {
                System.out.println("ERR " + a[0] + " " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            System.out.flush();
        }
    }
}
