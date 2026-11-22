package dev.cipherkey.core.merge;

import dev.cipherkey.core.model.Record;

import java.util.*;

/**
 * Fusión a tres bandas por campo. Nunca descarta una edición: si ambos lados cambiaron lo mismo de forma distinta,
 * conserva la versión local y adjunta la remota en {@link Record#conflict} para que el usuario decida.
 */
public final class Merger {
    private Merger() {}

    /**
     * @param needsPush {@code true} si el resultado difiere de la versión remota y hay que enviarlo
     * @param conflictFields campos en conflicto (vacío si no hay conflicto; {@code ["deleted"]} para editar-vs-borrar)
     */
    public record Result(Record record, boolean needsPush, List<String> conflictFields) {
        public boolean hasConflict() { return !conflictFields.isEmpty(); }
    }

    public static Result merge(Record base, Record local, Record remote, String deviceId, long now) {
        Record baseSafe = base != null ? base : new Record();
        if (local.deleted && remote.deleted) return new Result(local.copy(), false, List.of());

        if (remote.deleted) {
            if (sameContent(local, baseSafe)) return new Result(remote.copy(), false, List.of());
            Record kept = local.copy();
            kept.conflict = remote.copy();
            return new Result(kept, false, List.of("deleted"));
        }
        if (local.deleted) {
            if (sameContent(remote, baseSafe)) return new Result(local.copy(), true, List.of());
            Record kept = remote.copy();
            kept.conflict = local.copy();
            return new Result(kept, false, List.of("deleted"));
        }

        Record out = remote.copy();
        List<String> conflicts = new ArrayList<>();
        Map<String, Object> b = baseSafe.contentFields(), l = local.contentFields(), r = remote.contentFields();
        for (String f : l.keySet()) {
            Object bv = b.get(f), lv = l.get(f), rv = r.get(f);
            if (lv.equals(rv)) continue;
            if (lv.equals(bv)) continue;          // solo cambió el remoto: ya está en out
            if (rv.equals(bv)) { out.setContentField(f, lv); continue; } // solo cambió el local
            conflicts.add(f);
            out.setContentField(f, lv);           // por defecto se mantiene lo local; lo remoto queda en conflict
        }
        out.history = mergeHistory(local.history, remote.history);

        boolean contentChanged = !out.contentFields().equals(remote.contentFields())
                || !out.history.equals(remote.history);
        if (!conflicts.isEmpty()) {
            out.conflict = remote.copy();
            out.conflict.conflict = null;
            out.touch(deviceId, now);
            return new Result(out, false, List.copyOf(conflicts));
        }
        if (contentChanged) {
            out.rev = Math.max(local.rev, remote.rev);
            out.touch(deviceId, now);
        }
        return new Result(out, contentChanged, List.of());
    }

    /**
     * Resuelve un conflicto. {@code takeOther=true} adopta la versión adjunta (remota o lápida); {@code false} conserva la local.
     * El resultado queda listo para enviarse (llamar a {@code SyncEngine#markDirty}).
     */
    public static Record resolve(Record r, boolean takeOther, String deviceId, long now) {
        if (r.conflict == null) throw new IllegalStateException("El registro no tiene conflicto");
        Record out;
        if (takeOther) {
            out = r.conflict.copy();
            out.history = mergeHistory(r.history, out.history);
        } else {
            out = r.copy();
        }
        out.conflict = null;
        out.uuid = r.uuid;
        out.touch(deviceId, now);
        return out;
    }

    /** Duplica la versión en conflicto como registro nuevo («ambas»), conservando la local. */
    public static Record keepBoth(Record r, String deviceId, long now) {
        if (r.conflict == null || r.conflict.deleted) throw new IllegalStateException("No hay versión viva que duplicar");
        Record copy = r.conflict.copy();
        copy.conflict = null;
        copy.uuid = UUID.randomUUID().toString();
        copy.title = copy.title + " (conflicto)";
        copy.created = now;
        copy.rev = 1;
        copy.updatedAt = now;
        copy.updatedBy = deviceId;
        return copy;
    }

    private static boolean sameContent(Record a, Record b) {
        return a.deleted == b.deleted && a.contentFields().equals(b.contentFields());
    }

    private static List<Record.HistoryItem> mergeHistory(List<Record.HistoryItem> a, List<Record.HistoryItem> b) {
        LinkedHashSet<Record.HistoryItem> set = new LinkedHashSet<>(a);
        set.addAll(b);
        List<Record.HistoryItem> all = new ArrayList<>(set);
        all.sort(Comparator.comparingLong(Record.HistoryItem::replacedAt).reversed());
        return new ArrayList<>(all.subList(0, Math.min(10, all.size())));
    }
}
