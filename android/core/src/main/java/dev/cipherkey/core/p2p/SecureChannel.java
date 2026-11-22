package dev.cipherkey.core.p2p;

import dev.cipherkey.core.crypto.VaultCrypto;
import dev.cipherkey.core.sync.DeviceKey;
import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Canal cifrado y autenticado por clave de dispositivo entre dos dispositivos, sobre cualquier par de flujos (TCP en LAN,
 * RFCOMM en Bluetooth...). No requiere servidor ni grupo de sincronización: ver docs/adr/0002-proximidad-sin-servidor.md.
 *
 * <p>Apretón de manos (autenticación de código corto con compromiso, como ZRTP): el iniciador envía primero SOLO un
 * compromiso SHA-256 de su mitad (clave efímera X25519, nonce, clave pública Ed25519 e id); el respondedor contesta con
 * la suya; y solo entonces el iniciador la revela y el respondedor comprueba el compromiso. Así ninguno de los dos (ni un
 * intermediario) puede escoger su mitad después de ver la del otro para forzar un código de seguridad igual en los dos
 * lados: con un SAS de 6 dígitos, el intermediario acierta con probabilidad 1/10^6 por intento. El transcripto T cubre
 * todo lo enviado; las claves de sesión salen de X25519 (secreto hacia delante) y T; cada dispositivo firma T con su clave
 * Ed25519 (posesión de la clave). El SAS sale de T y de las dos claves públicas: ambos lados deben mostrar el mismo.
 * Este canal NO decide si el otro es de confianza: eso lo hace {@link PeerSync} con la clave fijada o el SAS confirmado.
 *
 * <p>Tramas: longitud(4) + AES-256-GCM(nonce aleatorio, AAD = dirección + contador). El contador estricto impide
 * repetir, reordenar o eliminar tramas.
 */
public final class SecureChannel implements Closeable {
    public static final int MAX_FRAME = 16 * 1024 * 1024;
    private static final byte[] MAGIC = {'C', 'K', 'P', '2', 'P', 2};
    private static final int MAX_ID = 128;
    private static final SecureRandom RNG = new SecureRandom();

    /** Quién es el otro extremo, con la clave probada (posee la privada), pero aún sin confianza fijada. */
    public record Peer(String deviceId, String publicKeyB64, String sas) {}

    private final DataInputStream in;
    private final DataOutputStream out;
    private final Closeable closer;
    private final boolean initiator;
    private byte[] kSend, kRecv;
    private long sendCtr, recvCtr;
    private Peer peer;
    private java.util.function.IntConsumer timeoutSetter = ms -> { };

    private SecureChannel(InputStream in, OutputStream out, Closeable closer, boolean initiator) {
        this.in = new DataInputStream(new BufferedInputStream(in));
        this.out = new DataOutputStream(new BufferedOutputStream(out));
        this.closer = closer;
        this.initiator = initiator;
    }

    public Peer peer() { return peer; }

    /** Quien abre el canal indica cómo cambiar el tiempo de espera de lectura (p. ej. {@code Socket#setSoTimeout}). */
    public void onReadTimeout(java.util.function.IntConsumer setter) { this.timeoutSetter = setter; }

    /** Amplía la espera de lectura (cuando el otro lado puede estar esperando a una persona). */
    public void setReadTimeout(int millis) { timeoutSetter.accept(millis); }

    /** Abre el canal como iniciador. */
    public static SecureChannel connect(InputStream in, OutputStream out, Closeable closer,
                                        DeviceKey identity, String deviceId) throws IOException {
        SecureChannel c = new SecureChannel(in, out, closer, true);
        try {
            c.handshake(identity, deviceId);
        } catch (EOFException e) {
            c.closeQuietly();
            throw new PeerAuthException("El dispositivo cerró la conexión: no acepta conexiones nuevas o no es CipherKey");
        } catch (IOException | RuntimeException e) {
            c.closeQuietly();
            throw e;
        } catch (GeneralSecurityException e) {
            c.closeQuietly();
            throw new PeerAuthException("Fallo criptográfico en el apretón de manos");
        }
        return c;
    }

    public static SecureChannel accept(InputStream in, OutputStream out, Closeable closer,
                                       DeviceKey identity, String deviceId) throws IOException {
        SecureChannel c = new SecureChannel(in, out, closer, false);
        try {
            c.handshake(identity, deviceId);
        } catch (IOException | RuntimeException e) {
            c.closeQuietly();
            throw e;
        } catch (GeneralSecurityException e) {
            c.closeQuietly();
            throw new PeerAuthException("Fallo criptográfico en el apretón de manos");
        }
        return c;
    }

    /** Mitad del apretón de manos de un lado: efímera, nonce, clave pública (32 bytes) e id. */
    private record Half(byte[] eph, byte[] nonce, byte[] pub, byte[] id) {
        byte[] commitment() {
            return VaultCrypto.sha256(concat("CKP2P-C1".getBytes(StandardCharsets.UTF_8), lp(eph), lp(nonce), lp(pub), lp(id)));
        }
        void write(DataOutputStream o) throws IOException {
            o.write(eph); o.write(nonce); o.write(pub);
            o.writeShort(id.length); o.write(id);
        }
    }

    private Half readHalf() throws IOException {
        byte[] e = readFully(32), n = readFully(16), p = readFully(32);
        int l = in.readUnsignedShort();
        if (l == 0 || l > MAX_ID) throw new PeerAuthException("Identificador de dispositivo no válido");
        return new Half(e, n, p, readFully(l));
    }

    private void handshake(DeviceKey identity, String myId) throws IOException, GeneralSecurityException {
        X25519PrivateKeyParameters eph = new X25519PrivateKeyParameters(RNG);
        byte[] myPub = Base64.getDecoder().decode(identity.publicBase64());
        Half mine = new Half(eph.generatePublicKey().getEncoded(), VaultCrypto.randomBytes(16), myPub, myId.getBytes(StandardCharsets.UTF_8));
        Half theirs;
        Half hi, hr;

        if (initiator) {
            out.write(MAGIC); out.write(mine.commitment()); out.flush();
            byte[] magic = readFully(MAGIC.length);
            if (!MessageDigest.isEqual(magic, MAGIC)) throw new PeerAuthException("No es un par de CipherKey compatible");
            theirs = readHalf();
            mine.write(out); out.flush(); // revela su mitad solo tras ver la del otro
            hi = mine; hr = theirs;
        } else {
            byte[] magic = readFully(MAGIC.length);
            byte[] commit = readFully(32);
            if (!MessageDigest.isEqual(magic, MAGIC)) throw new PeerAuthException("No es un par de CipherKey compatible");
            out.write(MAGIC); mine.write(out); out.flush();
            theirs = readHalf();
            if (!MessageDigest.isEqual(commit, theirs.commitment())) throw new PeerAuthException("Compromiso del apretón de manos no válido");
            hi = theirs; hr = mine;
        }

        byte[] shared = new byte[32];
        X25519Agreement agreement = new X25519Agreement();
        agreement.init(eph);
        agreement.calculateAgreement(new X25519PublicKeyParameters(theirs.eph, 0), shared, 0);
        byte[] t = VaultCrypto.sha256(concat("CKP2P-T2".getBytes(StandardCharsets.UTF_8),
                lp(hi.eph), lp(hi.nonce), lp(hi.pub), lp(hi.id), lp(hr.eph), lp(hr.nonce), lp(hr.pub), lp(hr.id)));
        byte[] base = concat(shared, t);
        byte[] k1 = VaultCrypto.hkdf(base, "ck-p2p-i2r");
        byte[] k2 = VaultCrypto.hkdf(base, "ck-p2p-r2i");
        kSend = initiator ? k1 : k2;
        kRecv = initiator ? k2 : k1;
        Arrays.fill(shared, (byte) 0);

        // Posesión de la clave: el iniciador firma primero; el respondedor firma solo si la del iniciador es válida.
        String theirPub = Base64.getEncoder().encodeToString(theirs.pub);
        byte[] mySig = identity.sign(concat(t, new byte[]{(byte) (initiator ? 'I' : 'R')}));
        Frame mineAuth = new Frame("auth").put("sig", Base64.getEncoder().encodeToString(mySig));
        Frame theirAuth;
        if (initiator) {
            sendFrame(mineAuth.encode());
            theirAuth = Frame.decode(recvFrame());
            verifySig(theirAuth, theirPub, t, (byte) 'R');
        } else {
            theirAuth = Frame.decode(recvFrame());
            verifySig(theirAuth, theirPub, t, (byte) 'I');
            sendFrame(mineAuth.encode());
        }

        byte[] sasHash = VaultCrypto.sha256(concat("CKP2P-SAS".getBytes(StandardCharsets.UTF_8), t, hi.pub, hr.pub));
        int v = ((sasHash[0] & 0x7f) << 24 | (sasHash[1] & 0xff) << 16 | (sasHash[2] & 0xff) << 8 | (sasHash[3] & 0xff)) % 1_000_000;
        peer = new Peer(new String(theirs.id, StandardCharsets.UTF_8), theirPub, String.format("%06d", v));
    }

    private static void verifySig(Frame f, String pub, byte[] t, byte role) throws PeerAuthException {
        try {
            if (!DeviceKey.verify(pub, concat(t, new byte[]{role}), Base64.getDecoder().decode(f.get("sig"))))
                throw new PeerAuthException("Firma del dispositivo no válida");
        } catch (RuntimeException e) {
            throw new PeerAuthException("Autenticación del par mal formada");
        }
    }

    // ------------------------------------------------------------------ tramas

    public synchronized void send(Frame f) throws IOException {
        try {
            sendFrame(f.encode());
        } catch (GeneralSecurityException e) {
            throw new IOException("No se pudo cifrar la trama");
        }
    }

    public Frame receive() throws IOException {
        try {
            return Frame.decode(recvFrame());
        } catch (GeneralSecurityException e) {
            throw new PeerAuthException("Trama no válida (manipulada o repetida)");
        }
    }

    private void sendFrame(byte[] plain) throws IOException, GeneralSecurityException {
        byte[] ct = VaultCrypto.encrypt(kSend, aad(initiator, sendCtr), plain);
        sendCtr++;
        out.writeInt(ct.length);
        out.write(ct);
        out.flush();
    }

    private byte[] recvFrame() throws IOException, GeneralSecurityException {
        int len = in.readInt();
        if (len < 0 || len > MAX_FRAME) throw new PeerAuthException("Trama demasiado grande");
        byte[] ct = readFully(len);
        byte[] plain = VaultCrypto.decrypt(kRecv, aad(!initiator, recvCtr), ct);
        recvCtr++;
        return plain;
    }

    private static byte[] aad(boolean fromInitiator, long ctr) {
        return java.nio.ByteBuffer.allocate(9).put((byte) (fromInitiator ? 'I' : 'R')).putLong(ctr).array();
    }

    // ------------------------------------------------------------------ utilidades

    private byte[] readFully(int n) throws IOException {
        byte[] b = new byte[n];
        in.readFully(b);
        return b;
    }

    /** Longitud (2 bytes) + datos: evita ambigüedades al concatenar campos en el transcripto. */
    private static byte[] lp(byte[] p) {
        byte[] o = new byte[p.length + 2];
        o[0] = (byte) (p.length >> 8); o[1] = (byte) p.length;
        System.arraycopy(p, 0, o, 2, p.length);
        return o;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] o = new byte[n];
        int i = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, o, i, p.length); i += p.length; }
        return o;
    }

    private void closeQuietly() {
        try { close(); } catch (IOException ignored) { }
    }

    @Override public void close() throws IOException {
        if (kSend != null) Arrays.fill(kSend, (byte) 0);
        if (kRecv != null) Arrays.fill(kRecv, (byte) 0);
        if (closer != null) closer.close();
    }
}
