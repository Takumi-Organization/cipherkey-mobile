package dev.cipherkey.core.p2p;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cipherkey.core.crypto.VaultCrypto;
import dev.cipherkey.core.merge.Merger;
import dev.cipherkey.core.model.Record;
import dev.cipherkey.core.model.SyncState;
import dev.cipherkey.core.sync.DeviceKey;
import dev.cipherkey.core.sync.SyncEngine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * Sincronización directa entre dos dispositivos ya autenticados por clave por {@link SecureChannel}. No hay servidor ni
 * grupo: ver docs/adr/0002-proximidad-sin-servidor.md.
 *
 * <p>Confianza: la primera vez que se ven, ambos muestran el mismo código de seguridad de 6 dígitos y el usuario confirma
 * en los dos; entonces cada uno fija la clave pública Ed25519 del otro. Las siguientes veces basta la clave fijada. Si
 * una clave fijada cambia, se aborta (posible suplantación). Mientras no haya vínculo no se envía nada de la bóveda (ni
 * siquiera el nombre del dispositivo, salvo que el usuario haya abierto la vinculación).
 *
 * <p>Datos: se intercambian resúmenes (uuid + hash del contenido), cada lado pide lo que le falta o difiere y los
 * registros se fusionan a tres bandas con {@link Merger} usando como base lo último que tuvo ese par. La PRIMERA fusión
 * con un par (sin base común: cada dispositivo tiene su propia bóveda) es una unión segura: antes de aplicar nada se
 * muestra un resumen que el usuario debe confirmar en ambos lados y se hace una copia de seguridad; después nunca se
 * borra ni sustituye una entrada (una lápida no mata a una entrada viva y el mismo uuid con contenido distinto queda
 * como conflicto para que el usuario decida).
 */
public final class PeerSync {
    private PeerSync() {}

    public record Result(String peerId, String peerName, int received, int sent, int conflicts, boolean newlyTrusted, boolean firstMerge) {}

    /** Qué va a pasar en la primera fusión, desde el punto de vista de este dispositivo. */
    public record Summary(String peerName, int mineOnly, int theirsOnly, int differing) {}

    /** Pide al usuario comparar el código en ambos dispositivos. Devuelve true si coincide. */
    @FunctionalInterface
    public interface SasConfirm { boolean confirm(String peerName, String sas) throws Exception; }

    /** Puerta de la primera fusión: confirmación del usuario y copia de seguridad previa. */
    public interface MergeGate {
        /** Muestra el resumen y devuelve true si el usuario acepta fusionar. */
        boolean approve(Summary summary) throws Exception;
        /** Copia de seguridad local de la bóveda; si lanza, la fusión no se hace. */
        void backup() throws Exception;
    }

    private static final int MAX_RECORDS = 100_000;
    private static final int LONG_WAIT_MS = 120_000;

    /**
     * @param allowNewPeer solo true cuando el usuario ha pedido vincular (iniciador) o ha abierto el modo vincular (respondedor)
     * @param gate         obligatorio para la primera fusión con un par; sin puerta, esa fusión se rechaza
     */
    public static Result run(SecureChannel ch, boolean initiator, Locked doc, boolean allowNewPeer, SasConfirm confirm,
                             MergeGate gate, LongSupplier clock) throws Exception {
        SecureChannel.Peer peer = ch.peer();
        String[] self = doc.run(d -> new String[]{d.link.deviceId, d.link.deviceName});
        if (peer.deviceId().equals(self[0])) throw new PeerAuthException("El otro extremo dice ser este mismo dispositivo");

        // 1. confianza
        boolean pinned = doc.run(d -> {
            SyncState.Peer p = d.link.peers.get(peer.deviceId());
            if (p == null) return false;
            if (!p.publicKey.equals(peer.publicKeyB64()))
                throw new PeerAuthException("La clave de «" + p.name + "» ha cambiado: posible suplantación. Desvincúlalo y vuelve a vincularlo");
            return true;
        });
        Frame mineHello = new Frame("hello").put("pinned", pinned).put("accepts", allowNewPeer);
        if (pinned || allowNewPeer) mineHello.put("name", self[1]); // a un desconocido no se le dice ni el nombre
        ch.send(mineHello);
        Frame hello = expect(ch, "hello");
        boolean theirPinned = hello.json.get("pinned").getAsBoolean();
        boolean needTrust = !pinned || !theirPinned;
        if (needTrust && (!allowNewPeer || !hello.json.get("accepts").getAsBoolean() || !hello.json.has("name"))) {
            ch.send(new Frame("deny").put("reason", "Dispositivo no vinculado: abre «Vincular dispositivo cercano» en ambos"));
            throw new PeerAuthException("Dispositivo no vinculado: abre «Vincular dispositivo cercano» en ambos dispositivos");
        }
        String peerName = hello.json.has("name") ? cleanName(hello.get("name")) : peer.deviceId();
        ch.setReadTimeout(LONG_WAIT_MS); // el otro lado puede estar esperando a una persona (código, resumen)
        boolean newly = false;
        if (needTrust) {
            boolean ok = confirm != null && confirm.confirm(peerName, peer.sas());
            ch.send(new Frame("trust").put("ok", ok));
            Frame theirs = expect(ch, "trust");
            if (!ok || !theirs.json.get("ok").getAsBoolean()) throw new PeerAuthException("Código de seguridad rechazado");
            doc.run(d -> {
                SyncState.Peer p = d.link.peers.computeIfAbsent(peer.deviceId(), k -> new SyncState.Peer());
                p.name = peerName;
                p.publicKey = peer.publicKeyB64();
                p.lastSync = 0; p.base.clear(); // vínculo nuevo: sin base común, la próxima fusión es una unión segura
                return null;
            });
            newly = !pinned;
        }
        final boolean firstMine = doc.run(d -> d.link.peers.get(peer.deviceId()).lastSync == 0);

        // 2. resúmenes
        record Snap(Map<String, Record> byUuid, Map<String, String> hash, Set<String> tombstones, Set<String> allUuids) {}
        Snap snap = doc.run(d -> {
            Map<String, Record> m = new LinkedHashMap<>();
            Map<String, String> h = new LinkedHashMap<>();
            Set<String> tomb = new HashSet<>(), all = new HashSet<>();
            for (Record r : d.records) {
                all.add(r.uuid);
                if (r.conflict != null) continue; // no se ofrece nada que esté pendiente de resolver
                Record c = r.copy();
                m.put(c.uuid, c);
                h.put(c.uuid, contentHash(c));
                if (c.deleted) tomb.add(c.uuid);
            }
            return new Snap(m, h, tomb, all);
        });
        JsonObject digest = new JsonObject();
        snap.hash().forEach(digest::addProperty);
        JsonArray tombs = new JsonArray();
        snap.tombstones().forEach(tombs::add);
        ch.send(new Frame("digest").put("d", digest).put("tomb", tombs).put("first", firstMine));
        Frame theirDigest = expect(ch, "digest");
        JsonObject td = theirDigest.json.getAsJsonObject("d");
        if (td.size() > MAX_RECORDS) throw new PeerAuthException("Resumen demasiado grande");
        final boolean firstMerge = firstMine || (theirDigest.json.has("first") && theirDigest.json.get("first").getAsBoolean());
        Set<String> theirTombs = new HashSet<>();
        if (theirDigest.json.has("tomb")) for (JsonElement e : theirDigest.json.getAsJsonArray("tomb")) theirTombs.add(e.getAsString());

        // 2b. primera fusión: resumen + confirmación en ambos lados + copia de seguridad, antes de tocar nada
        if (firstMerge) {
            int mineOnly = 0, theirsOnly = 0, differing = 0;
            for (var e : snap.hash().entrySet()) {
                if (snap.tombstones().contains(e.getKey())) continue;
                if (!td.has(e.getKey())) mineOnly++;
            }
            for (var e : td.entrySet()) {
                String mine = snap.hash().get(e.getKey());
                if (mine == null) { if (!theirTombs.contains(e.getKey()) && !snap.allUuids().contains(e.getKey())) theirsOnly++; }
                else if (!mine.equals(e.getValue().getAsString())) differing++;
            }
            boolean go = false;
            try {
                go = gate != null && gate.approve(new Summary(peerName, mineOnly, theirsOnly, differing));
                if (go) gate.backup();
            } catch (Exception e) {
                go = false;
            }
            ch.send(new Frame("go").put("ok", go));
            Frame theirGo = expect(ch, "go");
            if (!go) throw new PeerAuthException("Fusión cancelada o sin copia de seguridad: no se ha cambiado nada");
            if (!theirGo.json.get("ok").getAsBoolean()) throw new PeerAuthException("El otro dispositivo canceló la fusión: no se ha cambiado nada");
        }

        // 3. petición
        Map<String, Record> localNow = snap.byUuid();
        JsonArray want = new JsonArray();
        List<String> wanted = new ArrayList<>();
        for (var e : td.entrySet()) {
            String mine = snap.hash().get(e.getKey());
            if (mine == null || !mine.equals(e.getValue().getAsString())) { want.add(e.getKey()); wanted.add(e.getKey()); }
        }
        ch.send(new Frame("want").put("u", want));
        Frame theirWant = expect(ch, "want");

        // 4. registros
        JsonArray give = new JsonArray();
        List<String> givenIds = new ArrayList<>();
        final Snap snapNow = snap;
        int sent = 0;
        for (JsonElement u : theirWant.json.getAsJsonArray("u")) {
            Record r = localNow.get(u.getAsString());
            if (r == null) continue;
            give.add(r.toJson());
            givenIds.add(r.uuid);
            sent++;
        }
        ch.send(new Frame("records").put("r", give));
        Frame theirRecords = expect(ch, "records");

        // 5. aplicar con la bóveda en su estado actual
        Set<String> allowed = new HashSet<>(wanted);
        final int[] counts = new int[2];
        doc.run(d -> {
            SyncState.Peer ps = d.link.peers.get(peer.deviceId());
            if (ps == null) throw new PeerAuthException("El dispositivo se desvinculó durante la sincronización");
            String selfId = d.link.deviceId;
            long now = clock.getAsLong();
            for (JsonElement el : theirRecords.json.getAsJsonArray("r")) {
                Record remote = Record.fromJson(el.getAsJsonObject());
                if (!allowed.contains(remote.uuid)) continue; // solo lo pedido
                remote.conflict = null;
                Optional<Record> localOpt = d.find(remote.uuid);
                if (localOpt.isEmpty()) {
                    d.records.add(remote.copy());
                    SyncEngine.markDirty(d, remote);
                    counts[0]++;
                    ps.base.put(remote.uuid, remote.copy());
                    continue;
                }
                Record local = localOpt.get();
                if (local.conflict != null) continue;
                if (firstMerge && local.deleted != remote.deleted) {
                    // unión segura: una lápida de la otra bóveda nunca borra una entrada viva (ni al revés)
                    Record live = local.deleted ? remote : local;
                    if (local.deleted) {
                        d.records.set(d.records.indexOf(local), remote.copy());
                        SyncEngine.markDirty(d, remote);
                        counts[0]++;
                    }
                    ps.base.put(remote.uuid, live.copy());
                    continue;
                }
                Record base = firstMerge ? null : ps.base.get(remote.uuid);
                Merger.Result res = Merger.merge(base, local, remote, selfId, now);
                if (!contentHash(res.record()).equals(contentHash(local)) || res.hasConflict()) {
                    d.records.set(d.records.indexOf(local), res.record());
                    SyncEngine.markDirty(d, res.record());
                    counts[0]++;
                }
                if (res.hasConflict()) counts[1]++;
                ps.base.put(remote.uuid, remote.copy());
            }
            // lo que ya coincide, o que acabamos de entregar tal cual, es el nuevo punto común con este par
            for (var e : td.entrySet()) {
                Record mine = snapNow.byUuid().get(e.getKey());
                if (mine != null && e.getValue().getAsString().equals(snapNow.hash().get(e.getKey()))) ps.base.put(e.getKey(), mine.copy());
            }
            for (String u : givenIds) { Record mine = snapNow.byUuid().get(u); if (mine != null) ps.base.putIfAbsent(u, mine.copy()); }
            ps.lastSync = now;
            return null;
        });

        ch.send(new Frame("done"));
        expect(ch, "done");
        return new Result(peer.deviceId(), peerName, counts[0], sent, counts[1], newly, firstMerge);
    }

    /** El nombre lo elige el otro dispositivo: se limita y se limpia antes de mostrarlo o guardarlo. */
    private static String cleanName(String n) {
        String c = n.replaceAll("\\p{Cntrl}", " ").trim();
        if (c.isEmpty()) return "Dispositivo";
        return c.length() > 60 ? c.substring(0, 60) : c;
    }

    private static Frame expect(SecureChannel ch, String type) throws IOException {
        Frame f = ch.receive();
        if (f.type().equals("deny")) throw new PeerAuthException(f.get("reason"));
        if (!f.type().equals(type)) throw new PeerAuthException("Trama inesperada (" + f.type() + ", se esperaba " + type + ")");
        return f;
    }

    /** Hash del contenido sincronizable (sin metadatos de edición): dos registros iguales dan el mismo resumen. */
    public static String contentHash(Record r) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", r.uuid);
        o.addProperty("deleted", r.deleted);
        o.addProperty("title", r.title); o.addProperty("username", r.username); o.addProperty("password", r.password);
        o.addProperty("url", r.url); o.addProperty("notes", r.notes); o.addProperty("category", r.category);
        o.addProperty("favorite", r.favorite); o.addProperty("totpSecret", r.totpSecret);
        JsonArray tags = new JsonArray(); r.tags.forEach(tags::add); o.add("tags", tags);
        JsonArray custom = new JsonArray();
        for (Record.Custom c : r.custom) {
            JsonObject x = new JsonObject();
            x.addProperty("n", c.name()); x.addProperty("v", c.value()); x.addProperty("s", c.secret());
            custom.add(x);
        }
        o.add("custom", custom);
        return DeviceKey.hex(VaultCrypto.sha256(o.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
