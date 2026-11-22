package dev.cipherkey.core.format;

import dev.cipherkey.core.crypto.VaultCrypto;
import dev.cipherkey.core.crypto.VaultCrypto.KdfParams;

import javax.crypto.AEADBadTagException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * Contenedor binario {@code .ckey} (v2 y v3 comparten disposición; solo cambia la versión y el contenido).
 * <pre>
 * "CKEY"(4) | versión(1) | kdf(1)=1 Argon2id | memoriaKiB(4) | iteraciones(4) | paralelismo(1) | sal(32)
 * | nonce(12) | texto cifrado + tag GCM(16)
 * </pre>
 * La cabecera (todo antes del nonce) se autentica como AAD. Esta clase trabaja solo con bytes; la E/S la hace quien la use.
 */
public final class VaultContainer {
    public static final byte VERSION_2 = 2;
    public static final byte VERSION_3 = 3;
    private static final byte[] MAGIC = "CKEY".getBytes(StandardCharsets.US_ASCII);
    private static final byte KDF_ARGON2ID = 1;
    public static final int HEADER_LENGTH = MAGIC.length + 1 + 1 + 4 + 4 + 1 + VaultCrypto.SALT_LENGTH;
    private static final int MIN_FILE_LENGTH = HEADER_LENGTH + VaultCrypto.NONCE_LENGTH + 16;

    private VaultContainer() {}

    /** Fichero abierto: versión leída, cabecera, clave derivada y texto claro. El llamador borra {@code key}. */
    public record Opened(int version, byte[] header, byte[] key, byte[] plaintext) {}

    public static class FormatException extends Exception {
        public FormatException(String m) { super(m); }
    }

    public static class WrongPasswordException extends Exception {
        public WrongPasswordException(Throwable cause) { super("Clave maestra incorrecta o bóveda alterada", cause); }
    }

    public static byte[] newHeader(int version, KdfParams params, byte[] salt) {
        return ByteBuffer.allocate(HEADER_LENGTH)
                .put(MAGIC).put((byte) version).put(KDF_ARGON2ID)
                .putInt(params.memoryKiB()).putInt(params.iterations()).put((byte) params.parallelism())
                .put(salt).array();
    }

    /** Copia de la cabecera con otra versión (misma sal y parámetros). */
    public static byte[] withVersion(byte[] header, int version) {
        byte[] h = header.clone();
        h[4] = (byte) version;
        return h;
    }

    public static KdfParams paramsOf(byte[] header) {
        ByteBuffer b = ByteBuffer.wrap(header, 6, header.length - 6);
        return new KdfParams(b.getInt(), b.getInt(), b.get() & 0xFF);
    }

    public static byte[] saltOf(byte[] header) {
        return Arrays.copyOfRange(header, HEADER_LENGTH - VaultCrypto.SALT_LENGTH, HEADER_LENGTH);
    }

    /** Lee solo la versión sin derivar claves (para decidir si hay que migrar). */
    public static int peekVersion(byte[] content) throws FormatException {
        if (content.length < MIN_FILE_LENGTH) throw new FormatException("Archivo demasiado corto para ser una bóveda");
        for (int i = 0; i < MAGIC.length; i++) if (content[i] != MAGIC[i]) throw new FormatException("No es una bóveda CipherKey");
        return content[4];
    }

    public static Opened open(byte[] content, char[] password) throws FormatException, WrongPasswordException, GeneralSecurityException {
        int version = peekVersion(content);
        if (version != VERSION_2 && version != VERSION_3) throw new FormatException("Versión de bóveda no soportada: " + version);
        ByteBuffer buf = ByteBuffer.wrap(content, 5, content.length - 5);
        byte kdf = buf.get();
        if (kdf != KDF_ARGON2ID) throw new FormatException("KDF no soportada: " + kdf);
        KdfParams params = new KdfParams(buf.getInt(), buf.getInt(), buf.get() & 0xFF);
        if (!params.isSane()) throw new FormatException("Parámetros de derivación fuera de rango");
        byte[] header = Arrays.copyOfRange(content, 0, HEADER_LENGTH);
        byte[] key = VaultCrypto.deriveKey(password, saltOf(header), params);
        try {
            byte[] plaintext = VaultCrypto.decrypt(key, header, Arrays.copyOfRange(content, HEADER_LENGTH, content.length));
            return new Opened(version, header, key, plaintext);
        } catch (AEADBadTagException e) {
            Arrays.fill(key, (byte) 0);
            throw new WrongPasswordException(e);
        } catch (GeneralSecurityException e) {
            Arrays.fill(key, (byte) 0);
            throw e;
        }
    }

    /** Abre con la clave ya derivada (desbloqueo biométrico). Si la clave no corresponde lanza {@link WrongPasswordException}. */
    public static Opened openWithKey(byte[] content, byte[] key) throws FormatException, WrongPasswordException, GeneralSecurityException {
        int version = peekVersion(content);
        if (version != VERSION_2 && version != VERSION_3) throw new FormatException("Versión de bóveda no soportada: " + version);
        byte[] header = Arrays.copyOfRange(content, 0, HEADER_LENGTH);
        try {
            byte[] plaintext = VaultCrypto.decrypt(key, header, Arrays.copyOfRange(content, HEADER_LENGTH, content.length));
            return new Opened(version, header, key.clone(), plaintext);
        } catch (AEADBadTagException e) {
            throw new WrongPasswordException(e);
        }
    }

    /** Cabecera + nonce + cifrado. */
    public static byte[] seal(byte[] header, byte[] key, byte[] plaintext) throws GeneralSecurityException {
        byte[] body = VaultCrypto.encrypt(key, header, plaintext);
        return ByteBuffer.allocate(header.length + body.length).put(header).put(body).array();
    }
}
