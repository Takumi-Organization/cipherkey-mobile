package dev.cipherkey.core.sync;

import java.util.List;

/** Tipos del protocolo de sincronización. {@code blob} es siempre nonce+cifrado AES-GCM: el servidor no lo puede leer. */
public final class Protocol {
    private Protocol() {}

    public record Change(String uuid, long ver, long seq, String deviceId, boolean deleted, byte[] blob) {}

    public record PullResponse(List<Change> changes, long latestSeq, boolean more) {}

    public record PushOp(String opId, String uuid, long baseVer, boolean deleted, byte[] blob) {}

    /** {@code status}: "ok", "conflict" (con {@code current}) o "rejected". */
    public record PushResult(String opId, String status, long ver, long seq, Change current) {}

    public interface Transport {
        PullResponse pull(long since, int limit) throws java.io.IOException;
        List<PushResult> push(List<PushOp> ops) throws java.io.IOException;
    }
}
