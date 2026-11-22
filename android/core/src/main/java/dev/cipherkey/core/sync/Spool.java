package dev.cipherkey.core.sync;

import com.google.gson.*;
import dev.cipherkey.core.sync.Protocol.*;

import java.io.IOException;
import java.util.*;

/**
 * Cola de sincronización para cuando la bóveda está BLOQUEADA (WorkManager en segundo plano). Solo maneja sobres ya cifrados
 * con la clave de sincronización, que no se guarda fuera de la bóveda: quien la lee sin la clave no obtiene nada.
 * Envía las operaciones selladas al bloquear y descarga cambios; al desbloquear, {@link SyncEngine#integrate} los incorpora.
 */
public final class Spool {
    public record Sent(String uuid, byte[] blob) {}

    public long cursor;
    public List<PushOp> outbox = new ArrayList<>();
    public Map<String, Sent> sentById = new HashMap<>();
    public List<PushResult> results = new ArrayList<>();
    public List<Change> inbox = new ArrayList<>();

    /** Un ciclo en segundo plano: envía lo pendiente y descarga lo nuevo. No necesita ninguna clave de descifrado. */
    public void run(Transport t) throws IOException {
        List<PushOp> todo = new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (PushResult r : results) done.add(r.opId());
        for (PushOp op : outbox) if (!done.contains(op.opId())) todo.add(op);
        if (!todo.isEmpty()) {
            for (PushResult r : t.push(todo)) {
                results.add(r);
                if ("conflict".equals(r.status()) && r.current() != null) inbox.add(r.current());
            }
        }
        while (true) {
            PullResponse resp = t.pull(cursor, 200);
            inbox.addAll(resp.changes());
            for (Change c : resp.changes()) cursor = Math.max(cursor, c.seq());
            if (!resp.more() || resp.changes().isEmpty()) {
                if (!resp.more()) cursor = Math.max(cursor, resp.latestSeq());
                break;
            }
        }
    }

    public int pendingChanges() { return inbox.size(); }

    // ---- serialización (los blobs viajan en base64; nada en claro) ----
    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("cursor", cursor);
        JsonArray ob = new JsonArray();
        for (PushOp op : outbox) {
            JsonObject x = new JsonObject();
            x.addProperty("opId", op.opId()); x.addProperty("uuid", op.uuid()); x.addProperty("baseVer", op.baseVer());
            x.addProperty("deleted", op.deleted()); x.addProperty("blob", b64(op.blob()));
            ob.add(x);
        }
        o.add("outbox", ob);
        JsonArray rs = new JsonArray();
        for (PushResult r : results) {
            JsonObject x = new JsonObject();
            x.addProperty("opId", r.opId()); x.addProperty("status", r.status()); x.addProperty("ver", r.ver()); x.addProperty("seq", r.seq());
            rs.add(x);
        }
        o.add("results", rs);
        JsonArray in = new JsonArray();
        for (Change c : inbox) in.add(change(c));
        o.add("inbox", in);
        return o.toString();
    }

    public static Spool fromJson(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        Spool s = new Spool();
        s.cursor = o.get("cursor").getAsLong();
        for (JsonElement e : o.getAsJsonArray("outbox")) {
            JsonObject x = e.getAsJsonObject();
            PushOp op = new PushOp(x.get("opId").getAsString(), x.get("uuid").getAsString(), x.get("baseVer").getAsLong(),
                    x.get("deleted").getAsBoolean(), Base64.getDecoder().decode(x.get("blob").getAsString()));
            s.outbox.add(op);
            s.sentById.put(op.opId(), new Sent(op.uuid(), op.blob()));
        }
        for (JsonElement e : o.getAsJsonArray("results")) {
            JsonObject x = e.getAsJsonObject();
            s.results.add(new PushResult(x.get("opId").getAsString(), x.get("status").getAsString(), x.get("ver").getAsLong(), x.get("seq").getAsLong(), null));
        }
        for (JsonElement e : o.getAsJsonArray("inbox")) {
            JsonObject x = e.getAsJsonObject();
            s.inbox.add(new Change(x.get("uuid").getAsString(), x.get("ver").getAsLong(), x.get("seq").getAsLong(), x.get("deviceId").getAsString(),
                    x.get("deleted").getAsBoolean(), Base64.getDecoder().decode(x.get("blob").getAsString())));
        }
        return s;
    }

    private static JsonObject change(Change c) {
        JsonObject x = new JsonObject();
        x.addProperty("uuid", c.uuid()); x.addProperty("ver", c.ver()); x.addProperty("seq", c.seq());
        x.addProperty("deviceId", c.deviceId()); x.addProperty("deleted", c.deleted()); x.addProperty("blob", b64(c.blob()));
        return x;
    }

    private static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
}
