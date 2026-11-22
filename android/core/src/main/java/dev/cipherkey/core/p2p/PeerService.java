package dev.cipherkey.core.p2p;

import dev.cipherkey.core.model.LinkState;
import dev.cipherkey.core.sync.DeviceKey;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/**
 * Servicio de proximidad por red local, SIN servidor: escucha conexiones de otros dispositivos, anuncia su presencia por
 * multidifusión UDP, descubre a los demás y sincroniza directamente con los vinculados. Reutilizable por escritorio y
 * Android. Un dispositivo desconocido solo avanza mientras el usuario mantiene abierta la ventana de vincular, y aun así
 * hace falta confirmar el código de seguridad en ambos lados (ver docs/adr/0002-proximidad-sin-servidor.md).
 */
public final class PeerService implements Closeable {
    public static final int DEFAULT_PORT = 47890;
    public static final String GROUP = "239.255.67.75";
    public static final int DISCOVERY_PORT = 47891;
    private static final long BEACON_EVERY_MS = 4000;
    private static final long NEARBY_TTL_MS = 15000;
    private static final int SOCKET_TIMEOUT_MS = 20000;
    private static final int MAX_INCOMING = 8;

    /** Dispositivo de la bóveda visto en la red local. */
    public record Nearby(InetAddress address, int port, String idTagHex, long lastSeen) {}

    public interface Ui {
        /** Muestra el código y espera la decisión del usuario. {@code incoming}: la conexión la inició el otro. */
        boolean confirmSas(String peerName, String sas, boolean incoming) throws Exception;
    }

    public interface Listener {
        default void peersChanged() {}
        default void synced(PeerSync.Result r, boolean incoming) {}
        default void failed(String who, String message, boolean incoming) {}
    }

    private final Locked doc;
    private final Ui ui;
    private final Listener listener;
    private final LongSupplier clock;
    private final AttemptLimiter limiter;
    private final int requestedPort;
    private final boolean multicast;
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "cipherkey-p2p"); t.setDaemon(true); return t; });
    private final Map<String, Nearby> nearby = new ConcurrentHashMap<>();
    private final Semaphore slots = new Semaphore(MAX_INCOMING);
    private volatile ServerSocket server;
    private volatile MulticastSocket mcast;
    private volatile long pairingUntil;
    private volatile boolean closed;
    private volatile String defaultName = "Dispositivo";
    private volatile PeerSync.MergeGate gate;
    private volatile String discoveryError;

    /** Nombre con que se crea la identidad de proximidad la primera vez (si la bóveda aún no tiene una). */
    public void setDefaultDeviceName(String name) { if (name != null && !name.isBlank()) this.defaultName = name; }

    /** Puerta de la primera fusión con cada par (resumen + confirmación + copia de seguridad). Sin ella esa fusión se rechaza. */
    public void setMergeGate(PeerSync.MergeGate gate) { this.gate = gate; }

    /** Crea ya la identidad (y la guarda con la bóveda) para poder anunciarse y vincular. */
    public void ensureIdentity() throws Exception { identity(); }

    public PeerService(Locked doc, int port, boolean multicast, Ui ui, Listener listener, LongSupplier clock) {
        this.doc = doc; this.requestedPort = port; this.multicast = multicast; this.ui = ui; this.listener = listener; this.clock = clock;
        this.limiter = new AttemptLimiter(5, 10 * 60_000L, clock);
    }

    public int port() { return server == null ? 0 : server.getLocalPort(); }

    public void start() throws Exception {
        try {
            server = new ServerSocket(requestedPort);
        } catch (BindException e) {
            throw new IOException("el puerto " + requestedPort + " está ocupado por otro programa (¿hay otra copia de CipherKey abierta?)", e);
        }
        workers.execute(this::acceptLoop);
        if (multicast) {
            try {
                MulticastSocket ms = new MulticastSocket(DISCOVERY_PORT);
                ms.setReuseAddress(true);
                ms.setLoopbackMode(false);
                joinAll(ms);
                mcast = ms;
                workers.execute(this::listenBeacons);
                workers.execute(this::sendBeacons);
            } catch (IOException e) {
                // sin multidifusión (red restringida, sin interfaz): se puede seguir conectando por dirección
                mcast = null;
                discoveryError = "Sin descubrimiento automático (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                        + "): conecta escribiendo la dirección IP del otro dispositivo";
            }
        }
    }

    /** Motivo por el que no hay descubrimiento automático en la red (sin red multidifusión, puerto UDP ocupado...), o {@code null}. */
    public String discoveryError() { return discoveryError; }

    private static void joinAll(MulticastSocket ms) throws IOException {
        InetAddress g = InetAddress.getByName(GROUP);
        boolean any = false;
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            try {
                if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) continue;
                ms.joinGroup(new InetSocketAddress(g, DISCOVERY_PORT), ni);
                any = true;
            } catch (IOException ignored) { }
        }
        if (!any) throw new IOException("Ninguna interfaz con multidifusión");
    }

    /** Abre la ventana en que se aceptan dispositivos nuevos (hasta que pase el tiempo o se cierre el servicio). */
    public void openPairingWindow(long millis) { pairingUntil = clock.getAsLong() + millis; }

    public void closePairingWindow() { pairingUntil = 0; }

    public boolean pairingOpen() { return clock.getAsLong() < pairingUntil; }

    public List<Nearby> nearby() {
        long limit = clock.getAsLong() - NEARBY_TTL_MS;
        nearby.values().removeIf(n -> n.lastSeen() < limit);
        return new ArrayList<>(nearby.values());
    }

    // ------------------------------------------------------------------ saliente

    /** Sincroniza (y vincula si {@code newPeer}) con un dispositivo en {@code addr}. Bloqueante. */
    public PeerSync.Result syncWith(InetSocketAddress addr, boolean newPeer) throws Exception {
        Socket s = new Socket();
        try {
            s.connect(addr, 5000);
            s.setSoTimeout(SOCKET_TIMEOUT_MS);
            Identity id = identity();
            try (SecureChannel ch = SecureChannel.connect(s.getInputStream(), s.getOutputStream(), s, id.key, id.deviceId)) {
                ch.onReadTimeout(ms -> { try { s.setSoTimeout(ms); } catch (SocketException ignored) { } });
                PeerSync.Result r = PeerSync.run(ch, true, doc, newPeer,
                        (name, sas) -> ui != null && ui.confirmSas(name, sas, false), gate, clock);
                listener.synced(r, false);
                return r;
            }
        } catch (Exception e) {
            listener.failed(addr.getHostString() + ":" + addr.getPort(), friendly(e), false);
            throw e;
        } finally {
            s.close();
        }
    }

    // ------------------------------------------------------------------ entrante

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket s = server.accept();
                if (!slots.tryAcquire()) { s.close(); continue; } // saturado: se descarta sin gastar un hilo
                workers.execute(() -> { try { handle(s); } finally { slots.release(); } });
            } catch (IOException e) {
                if (closed) return;
            }
        }
    }

    private void handle(Socket s) {
        String src = s.getInetAddress().getHostAddress();
        try {
            if (limiter.blocked(src)) { s.close(); return; } // demasiados fallos: ni se contesta
            s.setSoTimeout(SOCKET_TIMEOUT_MS); // un desconocido no retiene el hilo más de esto antes de autenticarse
            Identity id = identity();
            try (SecureChannel ch = SecureChannel.accept(s.getInputStream(), s.getOutputStream(), s, id.key, id.deviceId)) {
                ch.onReadTimeout(ms -> { try { s.setSoTimeout(ms); } catch (SocketException ignored) { } });
                PeerSync.Result r = PeerSync.run(ch, false, doc, pairingOpen(),
                        (name, sas) -> ui != null && ui.confirmSas(name, sas, true), gate, clock);
                limiter.success(src);
                if (r.newlyTrusted()) closePairingWindow(); // el código de vincular sirve para un solo dispositivo
                listener.synced(r, true);
            }
        } catch (PeerAuthException e) {
            limiter.fail(src);
            listener.failed(src, friendly(e), true);
        } catch (Exception e) {
            if (!closed) listener.failed(src, friendly(e), true);
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private static String friendly(Exception e) {
        if (e instanceof PeerAuthException) return e.getMessage();
        if (e instanceof SocketTimeoutException) return "El otro dispositivo no respondió a tiempo";
        if (e instanceof ConnectException) return "No se pudo conectar con el dispositivo";
        if (e instanceof EOFException) return "El otro dispositivo cerró la conexión";
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    // ------------------------------------------------------------------ descubrimiento

    private void listenBeacons() {
        byte[] buf = new byte[64];
        while (!closed) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                mcast.receive(p);
                Identity id = identityCached();
                Beacon.Parsed b = Beacon.parse(p.getData(), p.getLength());
                if (b == null) continue;
                String tag = DeviceKey.hex(b.idTag());
                if (Arrays.equals(b.idTag(), Beacon.idTag(id.key.publicBase64()))) continue; // es el mío
                Nearby n = new Nearby(p.getAddress(), b.port(), tag, clock.getAsLong());
                if (nearby.put(tag, n) == null) listener.peersChanged();
            } catch (IOException e) {
                if (closed) return;
            } catch (Exception ignored) { }
        }
    }

    private void sendBeacons() {
        while (!closed) {
            try {
                Identity id = identityCached();
                byte[] data = Beacon.build(id.key.publicBase64(), port());
                InetAddress g = InetAddress.getByName(GROUP);
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    try {
                        if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) continue;
                        mcast.setNetworkInterface(ni);
                        mcast.send(new DatagramPacket(data, data.length, g, DISCOVERY_PORT));
                    } catch (IOException ignored) { }
                }
                Thread.sleep(BEACON_EVERY_MS);
            } catch (InterruptedException e) {
                return;
            } catch (Exception ignored) {
                try { Thread.sleep(BEACON_EVERY_MS); } catch (InterruptedException ie) { return; }
            }
        }
    }

    // ------------------------------------------------------------------ interno

    private record Identity(DeviceKey key, String deviceId, String name) {}

    private volatile Identity cachedId;
    private volatile long cachedIdAt;

    /** Para los anuncios periódicos: evita tocar (y guardar) la bóveda cada pocos segundos. */
    private Identity identityCached() throws Exception {
        Identity c = cachedId;
        if (c != null && clock.getAsLong() - cachedIdAt < 60_000) return c;
        c = identity();
        cachedId = c; cachedIdAt = clock.getAsLong();
        return c;
    }

    private Identity identity() throws Exception {
        String dn = defaultName;
        return doc.run(d -> {
            LinkState l = LinkState.ensure(d, dn);
            return new Identity(DeviceKey.fromPrivateBase64(l.devicePrivateKey), l.deviceId, l.deviceName);
        });
    }

    @Override public void close() {
        closed = true;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        if (mcast != null) mcast.close();
        workers.shutdownNow();
    }
}
