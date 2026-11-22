package dev.cipherkey.core.p2p;

import dev.cipherkey.core.crypto.VaultCrypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * Anuncio de presencia en la red local (UDP multidifusión). No hay grupo ni clave compartida: cualquiera puede emitirlo o
 * leerlo. Lleva solo el puerto TCP, un nonce y una etiqueta de 8 bytes derivada de la clave pública del dispositivo
 * (SHA-256 con separación de dominio): permite reconocer a un dispositivo ya vinculado sin revelar su nombre ni datos de la
 * bóveda. La etiqueta es un seudónimo estable visible para la red local. Un anuncio falso solo consigue que se intente una
 * conexión, que fallará en el apretón de manos o se detendrá en el código de seguridad.
 */
public final class Beacon {
    private Beacon() {}

    public static final int LENGTH = 4 + 2 + 8 + 8;
    private static final byte[] MAGIC = "CKB2".getBytes(StandardCharsets.US_ASCII);

    public record Parsed(int port, byte[] idTag) {}

    /** Etiqueta corta estable de un dispositivo a partir de su clave pública Ed25519 (base64). */
    public static byte[] idTag(String publicKeyB64) {
        byte[] pub = Base64.getDecoder().decode(publicKeyB64);
        byte[] label = "ck-p2p-vtag".getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[label.length + pub.length];
        System.arraycopy(label, 0, all, 0, label.length);
        System.arraycopy(pub, 0, all, label.length, pub.length);
        return Arrays.copyOf(VaultCrypto.sha256(all), 8);
    }

    public static byte[] build(String publicKeyB64, int port) {
        ByteBuffer b = ByteBuffer.allocate(LENGTH);
        b.put(MAGIC).putShort((short) port).put(VaultCrypto.randomBytes(8)).put(idTag(publicKeyB64));
        return b.array();
    }

    /** Devuelve null si no es un anuncio de CipherKey bien formado. */
    public static Parsed parse(byte[] data, int len) {
        if (len != LENGTH) return null;
        ByteBuffer b = ByteBuffer.wrap(data, 0, len);
        byte[] magic = new byte[4]; b.get(magic);
        if (!Arrays.equals(magic, MAGIC)) return null;
        int port = b.getShort() & 0xffff;
        if (port == 0) return null;
        b.position(b.position() + 8); // nonce
        byte[] tag = new byte[8]; b.get(tag);
        return new Parsed(port, tag);
    }
}
