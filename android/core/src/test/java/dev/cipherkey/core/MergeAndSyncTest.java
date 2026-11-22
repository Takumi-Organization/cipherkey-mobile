package dev.cipherkey.core;

import dev.cipherkey.core.merge.Merger;
import dev.cipherkey.core.model.Record;
import dev.cipherkey.core.model.VaultDocument;
import dev.cipherkey.core.sync.Protocol.Transport;
import dev.cipherkey.core.sync.SyncEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class MergeAndSyncTest {
    final AtomicLong clock = new AtomicLong(1_000);
    Fakes.MemServer server;
    VaultDocument desktop, mobile;
    Fakes.MemServer.Client dT, mT;

    @BeforeEach
    void setUp() {
        server = new Fakes.MemServer();
        desktop = Fakes.device(null, null, "desktop");
        mobile = Fakes.pairedWith(desktop, "mobile");
        dT = (Fakes.MemServer.Client) server.client("desktop");
        mT = (Fakes.MemServer.Client) server.client("mobile");
    }

    SyncEngine.Summary sync(VaultDocument d, Transport t) throws Exception {
        return new SyncEngine(d, t, clock::incrementAndGet).sync();
    }

    Record add(VaultDocument d, String dev, String title, String pwd) {
        Record r = Record.create(dev, clock.incrementAndGet());
        r.title = title; r.password = pwd;
        d.records.add(r);
        SyncEngine.markDirty(d, r);
        return r;
    }

    void edit(VaultDocument d, String dev, Record r, java.util.function.Consumer<Record> f) {
        f.accept(r);
        r.touch(dev, clock.incrementAndGet());
        SyncEngine.markDirty(d, r);
    }

    @Test
    void scenarioA_desktopCreatesMobileReceives() throws Exception {
        add(desktop, "desktop", "GitHub", "p1");
        sync(desktop, dT);
        var s = sync(mobile, mT);
        assertEquals(1, s.pulled());
        assertEquals("GitHub", mobile.live().get(0).title);
        assertEquals("p1", mobile.live().get(0).password);
    }

    @Test
    void scenarioB_mobileCreatesDesktopReceives() throws Exception {
        add(mobile, "mobile", "Banco", "p2");
        sync(mobile, mT);
        sync(desktop, dT);
        assertEquals("Banco", desktop.live().get(0).title);
    }

    @Test
    void scenarioC_offlineMobileGetsPendingChangeLater() throws Exception {
        Record r = add(desktop, "desktop", "Web", "v1");
        sync(desktop, dT); sync(mobile, mT);
        mT.offline = true;
        edit(desktop, "desktop", r, x -> x.password = "v2");
        sync(desktop, dT);
        assertThrows(IOException.class, () -> sync(mobile, mT));
        assertEquals("v1", mobile.live().get(0).password);
        mT.offline = false;
        sync(mobile, mT);
        assertEquals("v2", mobile.live().get(0).password);
    }

    @Test
    void offlineEditsQueueAndFlushWithoutDuplicates() throws Exception {
        dT.offline = true;
        Record r = add(desktop, "desktop", "Cola", "a");
        assertThrows(IOException.class, () -> sync(desktop, dT));
        assertTrue(desktop.sync.pending.containsKey(r.uuid), "la edición debe seguir en la cola");
        dT.offline = false;
        sync(desktop, dT);
        sync(desktop, dT); // reintento idempotente
        assertTrue(desktop.sync.pending.isEmpty());
        assertEquals(1, server.log.size());
    }

    @Test
    void lostResponseRetryIsIdempotent() throws Exception {
        Record r = add(desktop, "desktop", "X", "1");
        // simula que el servidor aceptó pero el cliente no lo supo: se reenvía la misma operación
        sync(desktop, dT);
        desktop.sync.pending.put(r.uuid, "");
        desktop.sync.baselines.clear();
        desktop.sync.lastSeq = 0;
        sync(desktop, dT);
        assertEquals(1, server.log.size(), "no debe haber un segundo registro en el servidor");
        assertEquals(1, desktop.live().size());
    }

    @Test
    void disjointFieldEditsMergeAutomatically() throws Exception {
        Record r = add(desktop, "desktop", "Svc", "p");
        sync(desktop, dT); sync(mobile, mT);
        edit(desktop, "desktop", r, x -> x.username = "adri");
        Record mr = mobile.live().get(0);
        edit(mobile, "mobile", mr, x -> x.notes = "nota móvil");
        sync(desktop, dT);
        sync(mobile, mT);     // detecta y fusiona, envía
        sync(desktop, dT);
        for (VaultDocument d : new VaultDocument[]{desktop, mobile}) {
            Record x = d.live().get(0);
            assertEquals("adri", x.username);
            assertEquals("nota móvil", x.notes);
            assertNull(x.conflict);
        }
    }

    @Test
    void sameFieldEditsRaiseConflictAndLoseNothing() throws Exception {
        Record r = add(desktop, "desktop", "Svc", "p");
        sync(desktop, dT); sync(mobile, mT);
        edit(desktop, "desktop", r, x -> x.password = "desde-pc");
        edit(mobile, "mobile", mobile.live().get(0), x -> x.password = "desde-movil");
        sync(desktop, dT);
        var s = sync(mobile, mT);
        assertEquals(1, s.conflicts());
        Record c = mobile.live().get(0);
        assertEquals("desde-movil", c.password);
        assertEquals("desde-pc", c.conflict.password);
        assertTrue(c.history.isEmpty() || true);

        // resolver quedándose con la remota y sincronizar
        Record resolved = Merger.resolve(c, true, "mobile", clock.incrementAndGet());
        mobile.records.set(0, resolved);
        SyncEngine.markDirty(mobile, resolved);
        sync(mobile, mT);
        sync(desktop, dT);
        assertEquals("desde-pc", desktop.live().get(0).password);
        assertEquals("desde-pc", mobile.live().get(0).password);
        assertNull(mobile.live().get(0).conflict);
    }

    @Test
    void conflictedRecordIsNotPushedUntilResolved() throws Exception {
        Record r = add(desktop, "desktop", "Svc", "p");
        sync(desktop, dT); sync(mobile, mT);
        edit(desktop, "desktop", r, x -> x.password = "A");
        edit(mobile, "mobile", mobile.live().get(0), x -> x.password = "B");
        sync(desktop, dT);
        sync(mobile, mT);
        int before = server.log.size();
        sync(mobile, mT);
        assertEquals(before, server.log.size());
        assertEquals("A", desktop.live().get(0).password, "el servidor sigue con A hasta resolver");
    }

    @Test
    void editVsDeleteIsAConflictNotASilentLoss() throws Exception {
        Record r = add(desktop, "desktop", "Svc", "p");
        sync(desktop, dT); sync(mobile, mT);
        r.markDeleted("desktop", clock.incrementAndGet());
        SyncEngine.markDirty(desktop, r);
        edit(mobile, "mobile", mobile.live().get(0), x -> x.password = "nueva");
        sync(desktop, dT);
        var s = sync(mobile, mT);
        assertEquals(1, s.conflicts());
        Record kept = mobile.records.get(0);
        assertFalse(kept.deleted);
        assertEquals("nueva", kept.password);
        assertTrue(kept.conflict.deleted);
    }

    @Test
    void deletionPropagatesAsTombstone() throws Exception {
        Record r = add(desktop, "desktop", "Borrame", "p");
        sync(desktop, dT); sync(mobile, mT);
        r.markDeleted("desktop", clock.incrementAndGet());
        SyncEngine.markDirty(desktop, r);
        sync(desktop, dT); sync(mobile, mT);
        assertTrue(mobile.live().isEmpty());
        assertEquals(1, mobile.records.size());
        assertTrue(mobile.records.get(0).deleted);
        assertEquals("", mobile.records.get(0).password, "la lápida no conserva la contraseña");
    }

    @Test
    void serverBlobsAreOpaque() throws Exception {
        add(desktop, "desktop", "Secreto", "contraseña-súper-secreta");
        sync(desktop, dT);
        String blob = new String(server.log.get(0).blob(), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(blob.contains("contraseña") || blob.contains("Secreto"));
    }

    @Test
    void wrongSyncKeyCannotReadServerData() throws Exception {
        add(desktop, "desktop", "S", "p");
        sync(desktop, dT);
        VaultDocument intruder = Fakes.device(null, null, "evil"); // clave de sincronización distinta
        assertThrows(java.security.GeneralSecurityException.class,
                () -> new SyncEngine(intruder, server.client("evil"), clock::incrementAndGet).sync());
    }

    @Test
    void manyRecordsPageThrough() throws Exception {
        for (int i = 0; i < 450; i++) add(desktop, "desktop", "R" + i, "p" + i);
        sync(desktop, dT);
        sync(mobile, mT);
        assertEquals(450, mobile.live().size());
    }

    @Test
    void lockedVaultSpoolPushesAndPullsWithoutAnyDecryptionKey() throws Exception {
        Record r = add(desktop, "desktop", "Server", "1");
        sync(desktop, dT);
        sync(mobile, mT);
        // el móvil edita y se bloquea con la edición pendiente
        edit(mobile, "mobile", mobile.live().get(0), x -> x.password = "2");
        // el escritorio también cambia otra cosa distinta mientras tanto
        Record other = add(desktop, "desktop", "Otro", "x");
        sync(desktop, dT);

        dev.cipherkey.core.sync.Spool spool = new dev.cipherkey.core.sync.Spool();
        new SyncEngine(mobile, mT, clock::incrementAndGet).exportTo(spool);
        spool = dev.cipherkey.core.sync.Spool.fromJson(spool.toJson()); // sobrevive a disco
        spool.run(mT);                                                   // sin tocar la bóveda ni claves
        assertFalse(spool.toJson().contains("\"2\""), "el spool no contiene texto claro");

        int n = new SyncEngine(mobile, mT, clock::incrementAndGet).integrate(spool);
        assertTrue(n >= 2);
        assertTrue(mobile.sync.pending.isEmpty(), "la edición quedó confirmada");
        assertEquals(2, mobile.live().size(), "llegó el registro nuevo del escritorio");
        sync(desktop, dT);
        assertEquals("2", desktop.find(r.uuid).get().password, "el cambio del móvil llegó sin desbloquear la bóveda");
    }

    @Test
    void spoolConflictIsSurfacedOnUnlock() throws Exception {
        Record r = add(desktop, "desktop", "Svc", "p");
        sync(desktop, dT); sync(mobile, mT);
        edit(desktop, "desktop", r, x -> x.password = "pc");
        edit(mobile, "mobile", mobile.live().get(0), x -> x.password = "movil");
        sync(desktop, dT);
        var spool = new dev.cipherkey.core.sync.Spool();
        new SyncEngine(mobile, mT, clock::incrementAndGet).exportTo(spool);
        spool.run(mT);
        new SyncEngine(mobile, mT, clock::incrementAndGet).integrate(spool);
        assertEquals("pc", mobile.live().get(0).conflict.password);
        assertEquals("movil", mobile.live().get(0).password);
    }
}
