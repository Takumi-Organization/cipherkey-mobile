package dev.cipherkey.core;

import dev.cipherkey.core.merge.Merger;
import dev.cipherkey.core.model.Record;
import dev.cipherkey.core.model.LinkState;
import dev.cipherkey.core.model.SyncState;
import dev.cipherkey.core.model.VaultDocument;
import dev.cipherkey.core.p2p.*;
import dev.cipherkey.core.sync.DeviceKey;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Sincronización directa entre dispositivos: convergencia, conflictos, confianza y ataques básicos. */
class PeerSyncTest {
    /** Un dispositivo: documento en memoria con cerrojo, servicio de proximidad en loopback y respuestas del usuario simuladas. */
    static final class Node implements Locked, AutoCloseable {
        final VaultDocument doc;
        final PeerService service;
        final List<String> sasSeen = Collections.synchronizedList(new ArrayList<>());
        final List<String> errors = Collections.synchronizedList(new ArrayList<>());
        volatile boolean acceptSas = true;
        final AtomicInteger syncs = new AtomicInteger();
        final List<PeerSync.Summary> summaries = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger backups = new AtomicInteger();
        volatile boolean approveMerge = true;
        volatile boolean backupFails = false;

        Node(VaultDocument doc, String id, String name) throws Exception {
            this.doc = doc;
            LinkState s = new LinkState();
            s.deviceId = id; s.deviceName = name; s.devicePrivateKey = DeviceKey.generate().privateBase64();
            doc.link = s; // SIN doc.sync: no hay servidor ni grupo
            service = new PeerService(this, 0, false,
                    (peer, sas, incoming) -> { sasSeen.add(sas); return acceptSas; },
                    new PeerService.Listener() {
                        @Override public void synced(PeerSync.Result r, boolean incoming) { syncs.incrementAndGet(); }
                        @Override public void failed(String who, String message, boolean incoming) { errors.add(message); }
                    }, System::currentTimeMillis);
            service.setMergeGate(new PeerSync.MergeGate() {
                @Override public boolean approve(PeerSync.Summary x) { summaries.add(x); return approveMerge; }
                @Override public void backup() throws Exception { if (backupFails) throw new IOException("sin espacio"); backups.incrementAndGet(); }
            });
            service.start();
        }

        @Override public synchronized <T> T run(Work<T> w) throws Exception { return w.apply(doc); }
        InetSocketAddress addr() { return new InetSocketAddress("127.0.0.1", service.port()); }
        @Override public void close() { service.close(); }
        Record find(String uuid) { return doc.find(uuid).orElse(null); }
    }

    static Record rec(String title, String pass) {
        Record r = Record.create("seed", 1000);
        r.title = title; r.password = pass; r.username = "u";
        return r;
    }

    /** Dos dispositivos con bóvedas DISTINTAS (cada una con su vaultId y clave), sin servidor ni grupo. */
    static Node[] pair() throws Exception {
        VaultDocument a = new VaultDocument();
        VaultDocument b = new VaultDocument();
        return new Node[]{new Node(a, "dev-A", "Portátil"), new Node(b, "dev-B", "Móvil")};
    }

    static void link(Node a, Node b) throws Exception {
        b.service.openPairingWindow(60_000);
        a.service.syncWith(b.addr(), true);
    }

    @Test void primerVinculoConfirmaElMismoCodigoYSincroniza() throws Exception {
        Node[] n = pair();
        try (Node a = n[0]; Node b = n[1]) {
            Record r = rec("Banco", "p1");
            a.doc.records.add(r);
            link(a, b);
            assertEquals(1, a.sasSeen.size());
            assertEquals(a.sasSeen.get(0), b.sasSeen.get(0), "el código de seguridad debe coincidir");
            assertTrue(a.sasSeen.get(0).matches("\\d{6}"));
            assertEquals("p1", b.find(r.uuid).password);
            assertTrue(a.doc.link.peers.containsKey("dev-B") && b.doc.link.peers.containsKey("dev-A"));
            assertNull(b.doc.sync, "no se ha creado ningún estado de servidor");
        }
    }

    @Test void siguienteSincronizacionNoPideCodigoYAplicaCambiosEnAmbosSentidos() throws Exception {
        Node[] n = pair();
        try (Node a = n[0]; Node b = n[1]) {
            Record r1 = rec("Uno", "x"), r2 = rec("Dos", "y");
            a.doc.records.add(r1);
            link(a, b);
            int sas = a.sasSeen.size();
            a.find(r1.uuid).password = "x2"; a.find(r1.uuid).touch("dev-A", 2000);
            b.doc.records.add(r2);
            a.service.syncWith(b.addr(), false);
            assertEquals(sas, a.sasSeen.size(), "ya vinculados: sin código");
            assertEquals("x2", b.find(r1.uuid).password);
            assertEquals("y", a.find(r2.uuid).password);
        }
    }

    @Test void camposDistintosSeFusionanYElMismoCampoDejaConflicto() throws Exception {
        Node[] n = pair();
        try (Node a = n[0]; Node b = n[1]) {
            Record r = rec("Cuenta", "base");
            a.doc.records.add(r);
            link(a, b);
            // A cambia el usuario, B cambia las notas: se fusionan
            a.find(r.uuid).username = "ana"; a.find(r.uuid).touch("dev-A", 2000);
            b.find(r.uuid).notes = "nota de B"; b.find(r.uuid).touch("dev-B", 2100);
            a.service.syncWith(b.addr(), false);
            for (Node x : List.of(a, b)) {
                assertEquals("ana", x.find(r.uuid).username);
                assertEquals("nota de B", x.find(r.uuid).notes);
                assertNull(x.find(r.uuid).conflict);
            }
            // mismo campo distinto: conflicto, y no se pierde ninguna de las dos versiones
            a.find(r.uuid).password = "de-A"; a.find(r.uuid).touch("dev-A", 3000);
            b.find(r.uuid).password = "de-B"; b.find(r.uuid).touch("dev-B", 3100);
            a.service.syncWith(b.addr(), false);
            Set<String> seen = new HashSet<>();
            for (Node x : List.of(a, b)) {
                Record c = x.find(r.uuid);
                seen.add(c.password);
                assertNotNull(c.conflict, "cada lado conserva la otra versión");
                seen.add(c.conflict.password);
            }
            assertEquals(Set.of("de-A", "de-B"), seen);
        }
    }

    @Test void borradoSePropagaComoLapida() throws Exception {
        Node[] n = pair();
        try (Node a = n[0]; Node b = n[1]) {
            Record r = rec("Temporal", "t");
            a.doc.records.add(r);
            link(a, b);
            a.find(r.uuid).markDeleted("dev-A", 5000);
            a.service.syncWith(b.addr(), false);
            assertTrue(b.find(r.uuid).deleted);
        }
    }

    @Test void codigoRechazadoNoVinculaNiIntercambiaDatos() throws Exception {
        Node[] n = pair();
        try (Node a = n[0]; Node b = n[1]) {
            Record r = rec("Secreto", "s");
            a.doc.records.add(r);
            b.acceptSas = false; // el usuario del segundo dispositivo ve que no coincide
            b.service.openPairingWindow(60_000);
            assertThrows(IOException.class, () -> a.service.syncWith(b.addr(), true));
            assertTrue(a.doc.link.peers.isEmpty() && b.doc.link.peers.isEmpty());
            assertNull(b.find(r.uuid));
        }
    }

    @Test void sinModoVincularUnDispositivoNuevoNoSeAcepta() throws Exception {
        Node[] n = pair();
        try (Node a = n[0]; Node b = n[1]) {
            a.doc.records.add(rec("X", "x"));
            // b no abrió la ventana de vincular
            assertThrows(IOException.class, () -> a.service.syncWith(b.addr(), true));
            assertTrue(b.doc.records.isEmpty());
            assertTrue(a.doc.link.peers.isEmpty());
        }
    }

    @Test void sinVinculoNoSeRevelaNadaDeLaBovedaNiElNombre() throws Exception {
        Node[] n = pair();
        try (Node a = n[0]; Node b = n[1]) {
            b.doc.records.add(rec("Secreto de B", "no-debe-salir"));
            DeviceKey k = DeviceKey.generate();
            try (Socket sock = new Socket()) {
                sock.connect(b.addr(), 2000);
                sock.setSoTimeout(3000);
                try (SecureChannel ch = SecureChannel.connect(sock.getInputStream(), sock.getOutputStream(), sock, k, "extraño")) {
                    ch.send(new Frame("hello").put("pinned", false).put("accepts", false));
                    Frame h = ch.receive();
                    assertEquals("hello", h.type());
                    assertFalse(h.json.has("name"), "a un desconocido no se le dice ni el nombre del dispositivo");
                    assertFalse(h.json.get("accepts").getAsBoolean());
                    assertEquals("deny", ch.receive().type());
                }
            }
            assertTrue(b.doc.link.peers.isEmpty());
            assertTrue(b.sasSeen.isEmpty(), "sin ventana de vincular, ni siquiera se muestra un código");
        }
    }

    @Test void intermediarioActivoObtieneCodigosDistintosEnCadaLado() throws Exception {
        // M se interpone entre A (que cree hablar con B) y B: hace de respondedor con A y de iniciador con B.
        Node[] n = pair();
        try (Node a = n[0]; Node b = n[1]; ServerSocket mitm = new ServerSocket(0)) {
            b.service.openPairingWindow(60_000);
            DeviceKey mk = DeviceKey.generate();
            String[] sasToA = new String[1], sasToB = new String[1];
            Thread t = new Thread(() -> {
                try (Socket fromA = mitm.accept(); Socket toB = new Socket()) {
                    toB.connect(b.addr(), 2000);
                    fromA.setSoTimeout(3000); toB.setSoTimeout(3000);
                    // el compromiso del iniciador llega antes de que M tenga que elegir nada: M no puede ajustar su mitad
                    SecureChannel ca = SecureChannel.accept(fromA.getInputStream(), fromA.getOutputStream(), fromA, mk, "M");
                    SecureChannel cb = SecureChannel.connect(toB.getInputStream(), toB.getOutputStream(), toB, mk, "M");
                    sasToA[0] = ca.peer().sas(); sasToB[0] = cb.peer().sas();
                } catch (Exception ignored) { }
            });
            t.setDaemon(true); t.start();
            assertThrows(Exception.class, () -> a.service.syncWith(new InetSocketAddress("127.0.0.1", mitm.getLocalPort()), true));
            t.join(5000);
            assertNotNull(sasToA[0]); assertNotNull(sasToB[0]);
            assertNotEquals(sasToA[0], sasToB[0], "el usuario vería códigos distintos y rechazaría");
            assertTrue(a.doc.link.peers.isEmpty() && b.doc.link.peers.isEmpty());
        }
    }

    @Test void unionDeDosBovedasSinBaseComunNoPierdeNingunaEntrada() throws Exception {
        Node[] n = pair();
        try (Node a = n[0]; Node b = n[1]) {
            Record a1 = rec("Solo en A 1", "a1"), a2 = rec("Solo en A 2", "a2"), b1 = rec("Solo en B", "b1");
            Record sa = rec("Compartida", "pa"); sa.uuid = "mismo-uuid"; sa.notes = "";
            Record sb = rec("Compartida", "pb"); sb.uuid = "mismo-uuid"; sb.notes = "nota de B";
            Record same = rec("Idéntica", "ii"); same.uuid = "idem";
            Record same2 = same.copy();
            a.doc.records.addAll(List.of(a1, a2, sa, same));
            b.doc.records.addAll(List.of(b1, sb, same2));
            link(a, b);

            for (Node x : List.of(a, b)) {
                assertNotNull(x.find(a1.uuid)); assertNotNull(x.find(a2.uuid)); assertNotNull(x.find(b1.uuid));
                assertEquals("ii", x.find("idem").password);
            }
            assertEquals(5, a.doc.records.size());
            assertEquals(5, b.doc.records.size());
            // mismo uuid y contenido distinto: ninguna versión se pierde
            Set<String> seen = new HashSet<>();
            for (Node x : List.of(a, b)) {
                Record c = x.find("mismo-uuid");
                assertNotNull(c.conflict, "queda como conflicto para que lo resuelva el usuario");
                seen.add(c.password); seen.add(c.conflict.password);
            }
            assertEquals(Set.of("pa", "pb"), seen);
            // resumen mostrado y copia de seguridad previa en cada lado
            assertEquals(1, a.backups.get()); assertEquals(1, b.backups.get());
            PeerSync.Summary sA = a.summaries.get(0), sB = b.summaries.get(0);
            assertEquals(2, sA.mineOnly()); assertEquals(1, sA.theirsOnly()); assertEquals(1, sA.differing());
            assertEquals(1, sB.mineOnly()); assertEquals(2, sB.theirsOnly()); assertEquals(1, sB.differing());
        }
    }

    @Test void unaLapidaDeLaOtraBovedaNuncaBorraUnaEntradaViva() throws Exception {
        Node[] n = pair();
        try (Node a = n[0]; Node b = n[1]) {
            Record viva = rec("Importante", "vital");
            Record muerta = viva.copy(); muerta.markDeleted("otro", 9000);
            a.doc.records.add(viva);
            b.doc.records.add(muerta);
            link(a, b);
            assertFalse(a.find(viva.uuid).deleted, "la entrada viva de A sigue viva");
            assertFalse(b.find(viva.uuid).deleted, "y B la recupera");
            assertEquals("vital", b.find(viva.uuid).password);
        }
    }

    @Test void sinConfirmarElResumenNoSeCambiaNada() throws Exception {
        Node[] n = pair();
        try (Node a = n[0]; Node b = n[1]) {
            a.doc.records.add(rec("De A", "a"));
            b.doc.records.add(rec("De B", "b"));
            b.approveMerge = false; // el usuario de B no acepta
            b.service.openPairingWindow(60_000);
            assertThrows(IOException.class, () -> a.service.syncWith(b.addr(), true));
            assertEquals(1, a.doc.records.size());
            assertEquals(1, b.doc.records.size());
            assertEquals(0, b.backups.get(), "quien no aprueba no hace copia ni fusiona");
        }
    }

    @Test void siLaCopiaDeSeguridadFallaNoSeFusiona() throws Exception {
        Node[] n = pair();
        try (Node a = n[0]; Node b = n[1]) {
            a.doc.records.add(rec("De A", "a"));
            a.backupFails = true;
            b.service.openPairingWindow(60_000);
            assertThrows(IOException.class, () -> a.service.syncWith(b.addr(), true));
            assertTrue(b.doc.records.isEmpty());
            assertEquals(1, a.doc.records.size());
        }
    }

    @Test void sinPuertaDeFusionLaPrimeraFusionSeRechaza() throws Exception {
        Node[] n = pair();
        try (Node a = n[0]; Node b = n[1]) {
            a.doc.records.add(rec("De A", "a"));
            a.service.setMergeGate(null);
            b.service.openPairingWindow(60_000);
            assertThrows(IOException.class, () -> a.service.syncWith(b.addr(), true));
            assertTrue(b.doc.records.isEmpty());
        }
    }

    @Test void despuesDeLaPrimeraFusionNoSePideResumenNiCopia() throws Exception {
        Node[] n = pair();
        try (Node a = n[0]; Node b = n[1]) {
            link(a, b);
            a.doc.records.add(rec("Nueva", "n"));
            a.service.syncWith(b.addr(), false);
            assertEquals(1, a.summaries.size());
            assertEquals(1, a.backups.get());
            assertEquals(1, b.doc.records.size());
        }
    }

    @Test void desvincularCortaLaSincronizacionYVolverAVincularExigeOtraVezCodigoYResumen() throws Exception {
        Node[] n = pair();
        try (Node a = n[0]; Node b = n[1]) {
            Record r = rec("Dato", "d");
            a.doc.records.add(r);
            link(a, b);
            b.doc.link.peers.remove("dev-A"); // el usuario de B desvincula
            a.find(r.uuid).password = "cambio"; a.find(r.uuid).touch("dev-A", 5000);
            assertThrows(IOException.class, () -> a.service.syncWith(b.addr(), false), "B ya no confía en A");
            assertEquals("d", b.find(r.uuid).password);
            // A sigue creyendo que B está vinculado; al volver a vincular se pide código y se hace otra unión segura
            int sas = a.sasSeen.size();
            link(a, b);
            assertEquals(sas + 1, a.sasSeen.size());
            assertEquals(2, a.summaries.size());
            assertEquals(2, b.summaries.size());
            // sin base común tras desvincular, la edición de A frente al valor antiguo de B es un conflicto: no se pierde ninguna
            Record rb = b.find(r.uuid);
            assertEquals(Set.of("d", "cambio"), Set.of(rb.password, rb.conflict == null ? "" : rb.conflict.password));
        }
    }

    /** Proxy TCP que permite observar, alterar o grabar los bytes entre dos nodos. */
    static final class Proxy implements AutoCloseable {
        final ServerSocket ss;
        final ByteArrayOutputStream clientToServer = new ByteArrayOutputStream();
        volatile int flipFrame = -1; // número de trama cifrada (cliente->servidor, tras el apretón de manos) cuyo byte 20 se altera
        Proxy(InetSocketAddress target) throws IOException {
            ss = new ServerSocket(0);
            Thread t = new Thread(() -> {
                try {
                    Socket c = ss.accept();
                    Socket s = new Socket(target.getAddress(), target.getPort());
                    pump(c.getInputStream(), s.getOutputStream(), true);
                    pump(s.getInputStream(), c.getOutputStream(), false);
                } catch (IOException ignored) { }
            });
            t.setDaemon(true); t.start();
        }
        private void pump(InputStream in, OutputStream out, boolean record) {
            Thread t = new Thread(() -> {
                try {
                    // apretón de manos del iniciador: 6 (magia) + 32 (compromiso) + 32+16+32+2+5 (mitad revelada, id de 5 letras)
                    final int handshake = 125;
                    int pos = 0, b, frame = 0, frameStart = -1, frameLen = 0;
                    while ((b = in.read()) >= 0) {
                        if (record) {
                            synchronized (clientToServer) { clientToServer.write(b); }
                            if (pos >= handshake) {
                                if (frameStart < 0) frameStart = pos;
                                int off = pos - frameStart;
                                if (off == 3) { byte[] r = clientToServer.toByteArray(); frameLen = java.nio.ByteBuffer.wrap(r, frameStart, 4).getInt(); }
                                if (off == 4 + 20 && frame == flipFrame) b ^= 1;
                                if (off >= 3 && off == 3 + frameLen) { frame++; frameStart = -1; }
                            }
                            pos++;
                        }
                        out.write(b); out.flush();
                    }
                } catch (IOException ignored) { }
            });
            t.setDaemon(true); t.start();
        }
        InetSocketAddress addr() { return new InetSocketAddress("127.0.0.1", ss.getLocalPort()); }
        byte[] recorded() { synchronized (clientToServer) { return clientToServer.toByteArray(); } }
        @Override public void close() throws IOException { ss.close(); }
    }

    @Test void unBitAlteradoEnLaRedHaceFallarLaSesionSinAplicarNada() throws Exception {
        Node[] n = pair();
        try (Node a = n[0]; Node b = n[1]) {
            Record r = rec("Dato", "d");
            a.doc.records.add(r);
            link(a, b); // vinculados
            a.find(r.uuid).password = "nuevo"; a.find(r.uuid).touch("dev-A", 9000);
            try (Proxy p = new Proxy(b.addr())) {
                p.flipFrame = 2; // una trama cifrada de la sesión de sincronización (no del apretón de manos)
                assertThrows(IOException.class, () -> a.service.syncWith(p.addr(), false));
            }
            assertEquals("d", b.find(r.uuid).password, "una sesión manipulada no cambia nada");
        }
    }

    @Test void repetirUnaSesionGrabadaNoSirve() throws Exception {
        Node[] n = pair();
        try (Node a = n[0]; Node b = n[1]) {
            Record r = rec("Dato", "d");
            a.doc.records.add(r);
            link(a, b);
            a.find(r.uuid).password = "cambio"; a.find(r.uuid).touch("dev-A", 9000);
            byte[] recorded;
            try (Proxy p = new Proxy(b.addr())) {
                a.service.syncWith(p.addr(), false);
                Thread.sleep(200);
                recorded = p.recorded();
            }
            assertEquals("cambio", b.find(r.uuid).password);
            // el atacante repite lo que A envió, contra B
            b.find(r.uuid).password = "otro"; b.find(r.uuid).touch("dev-B", 9500);
            b.errors.clear();
            try (Socket s = new Socket("127.0.0.1", b.service.port())) {
                s.setSoTimeout(3000);
                s.getOutputStream().write(recorded);
                s.getOutputStream().flush();
                try { s.getInputStream().readAllBytes(); } catch (IOException ignored) { }
            }
            Thread.sleep(300);
            assertEquals("otro", b.find(r.uuid).password, "la repetición no debe aplicar nada");
            assertFalse(b.errors.isEmpty(), "B debe registrar el intento como fallo de autenticación");
        }
    }

    @Test void variosIntentosFallidosBloqueanElOrigen() throws Exception {
        Node[] n = pair();
        try (Node a = n[0]; Node b = n[1]) {
            for (int i = 0; i < 6; i++) {
                try (Socket s = new Socket("127.0.0.1", b.service.port())) {
                    s.getOutputStream().write(new byte[80]); // basura
                    s.getOutputStream().flush();
                    s.setSoTimeout(2000);
                    try { s.getInputStream().readAllBytes(); } catch (IOException ignored) { }
                }
            }
            Thread.sleep(200);
            int before = b.errors.size();
            try (Socket s = new Socket("127.0.0.1", b.service.port())) {
                s.getOutputStream().write(new byte[80]);
                s.setSoTimeout(2000);
                try { s.getInputStream().readAllBytes(); } catch (IOException ignored) { }
            }
            Thread.sleep(200);
            assertEquals(before, b.errors.size(), "pasado el límite, el origen se rechaza sin ni siquiera procesarlo");
            // y un par legítimo desde la misma IP también queda bloqueado un tiempo
            assertThrows(IOException.class, () -> a.service.syncWith(b.addr(), true));
        }
    }

    @Test void claveFijadaQueCambiaAbortaPorPosibleSuplantacion() throws Exception {
        Node[] n = pair();
        try (Node a = n[0]; Node b = n[1]) {
            link(a, b);
            // A recuerda otra clave pública para B (como si alguien la hubiera cambiado)
            a.doc.link.peers.get("dev-B").publicKey = DeviceKey.generate().publicBase64();
            PeerAuthException e = assertThrows(PeerAuthException.class, () -> a.service.syncWith(b.addr(), false));
            assertTrue(e.getMessage().contains("cambiado"));
        }
    }

    @Test void anuncioDeDescubrimientoNoRevelaNadaMasQueUnaEtiquetaYElPuerto() {
        String pub = DeviceKey.generate().publicBase64();
        byte[] beacon = Beacon.build(pub, 47890);
        assertEquals(Beacon.LENGTH, beacon.length);
        Beacon.Parsed ok = Beacon.parse(beacon, beacon.length);
        assertNotNull(ok);
        assertEquals(47890, ok.port());
        assertArrayEquals(Beacon.idTag(pub), ok.idTag());
        assertFalse(Arrays.equals(Beacon.idTag(pub), Beacon.idTag(DeviceKey.generate().publicBase64())));
        assertNull(Beacon.parse(new byte[Beacon.LENGTH], Beacon.LENGTH));
        assertNull(Beacon.parse(beacon, beacon.length - 1));
    }

    @Test void laIdentidadDeProximidadSeCreaSinServidorSePersisteYNoSeExporta() {
        VaultDocument d = new VaultDocument();
        assertNull(d.sync);
        LinkState l = LinkState.ensure(d, "Mi equipo");
        assertEquals("Mi equipo", l.deviceName);
        assertSame(l, LinkState.ensure(d, "otro"));
        SyncState.Peer p = new SyncState.Peer(); p.name = "Móvil"; p.publicKey = "k";
        l.peers.put("m", p);
        VaultDocument back = VaultDocument.fromBytes(d.toBytes());
        assertEquals(l.deviceId, back.link.deviceId);
        assertEquals(l.devicePrivateKey, back.link.devicePrivateKey);
        assertEquals("Móvil", back.link.peers.get("m").name);
        assertNull(d.withoutSync().link, "la identidad y los vínculos no viajan en una exportación");
    }

    @Test void siLaBovedaYaSincronizaConServidorSeReutilizaSuIdentidad() {
        VaultDocument d = new VaultDocument();
        d.sync = new SyncState();
        d.sync.deviceId = "srv-id"; d.sync.deviceName = "Portátil"; d.sync.devicePrivateKey = DeviceKey.generate().privateBase64();
        LinkState l = LinkState.ensure(d, "x");
        assertEquals("srv-id", l.deviceId);
        assertEquals(d.sync.devicePrivateKey, l.devicePrivateKey);
    }

    @Test void limitadorDeIntentosCaducaConElTiempo() {
        long[] now = {0};
        AttemptLimiter l = new AttemptLimiter(3, 1000, () -> now[0]);
        for (int i = 0; i < 3; i++) l.fail("x");
        assertTrue(l.blocked("x"));
        assertFalse(l.blocked("y"));
        now[0] = 2000;
        assertFalse(l.blocked("x"));
        l.fail("x"); l.success("x");
        assertFalse(l.blocked("x"));
    }
}
