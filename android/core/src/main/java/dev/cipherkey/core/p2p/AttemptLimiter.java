package dev.cipherkey.core.p2p;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Límite de intentos fallidos por origen: pasado el máximo en la ventana, se rechaza sin responder hasta que caduque. */
public final class AttemptLimiter {
    private final int max;
    private final long windowMillis;
    private final LongSupplier clock;
    private final Map<String, ArrayDeque<Long>> fails = new HashMap<>();

    public AttemptLimiter(int max, long windowMillis, LongSupplier clock) {
        this.max = max; this.windowMillis = windowMillis; this.clock = clock;
    }

    public synchronized boolean blocked(String source) {
        ArrayDeque<Long> q = fails.get(source);
        if (q == null) return false;
        prune(q);
        return q.size() >= max;
    }

    public synchronized void fail(String source) {
        ArrayDeque<Long> q = fails.computeIfAbsent(source, k -> new ArrayDeque<>());
        prune(q);
        q.addLast(clock.getAsLong());
    }

    public synchronized void success(String source) {
        fails.remove(source);
    }

    private void prune(ArrayDeque<Long> q) {
        long limit = clock.getAsLong() - windowMillis;
        while (!q.isEmpty() && q.peekFirst() < limit) q.removeFirst();
    }
}
