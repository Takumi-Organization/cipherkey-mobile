package dev.cipherkey.core;

import dev.cipherkey.core.model.*;
import dev.cipherkey.core.model.Record;
import dev.cipherkey.core.sync.Protocol.*;

import java.util.*;

/** Servidor en memoria con la misma semántica que el real (versión por registro, 409, idempotencia por opId, seq global). */
public final class Fakes {
    private Fakes() {}

    public static final class MemServer {
        public final Map<String, Change> heads = new LinkedHashMap<>();
        public final List<Change> log = new ArrayList<>();
        public final Map<String, PushResult> done = new HashMap<>();
        public long seq;
        public int pushCalls;

        public Transport client(String deviceId) { return new Client(deviceId); }

        final class Client implements Transport {
            final String dev;
            public boolean offline;
            Client(String d) { dev = d; }

            public PullResponse pull(long since, int limit) throws java.io.IOException {
                if (offline) throw new java.io.IOException("sin red");
                List<Change> out = new ArrayList<>();
                for (Change c : heads.values()) if (c.seq() > since) out.add(c);
                out.sort(Comparator.comparingLong(Change::seq));
                boolean more = out.size() > limit;
                if (more) out = out.subList(0, limit);
                return new PullResponse(out, seq, more);
            }

            public List<PushResult> push(List<PushOp> ops) throws java.io.IOException {
                if (offline) throw new java.io.IOException("sin red");
                pushCalls++;
                List<PushResult> res = new ArrayList<>();
                for (PushOp op : ops) {
                    PushResult prev = done.get(op.opId());
                    if (prev != null) { res.add(prev); continue; }
                    Change head = heads.get(op.uuid());
                    long ver = head == null ? 0 : head.ver();
                    PushResult r;
                    if (ver != op.baseVer()) {
                        r = new PushResult(op.opId(), "conflict", ver, 0, head);
                    } else {
                        Change c = new Change(op.uuid(), ver + 1, ++seq, dev, op.deleted(), op.blob());
                        heads.put(op.uuid(), c);
                        log.add(c);
                        r = new PushResult(op.opId(), "ok", c.ver(), c.seq(), null);
                        done.put(op.opId(), r);
                    }
                    res.add(r);
                }
                return res;
            }
        }
    }

    public static VaultDocument device(String syncKeyFrom, String vaultId, String deviceId) {
        VaultDocument d = new VaultDocument();
        if (syncKeyFrom != null) { d.syncKey = syncKeyFrom; d.vaultId = vaultId; }
        d.sync = new SyncState();
        d.sync.deviceId = deviceId;
        return d;
    }

    public static VaultDocument pairedWith(VaultDocument other, String deviceId) {
        return device(other.syncKey, other.vaultId, deviceId);
    }
}
