package dev.cipherkey.core.crypto;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.bouncycastle.crypto.params.HKDFParameters;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/** Primitivas: Argon2id (derivación), AES-256-GCM (cifrado autenticado), HKDF-SHA256 y SHA-256. No modifica los arrays recibidos. */
public final class VaultCrypto {
    public static final int KEY_LENGTH = 32;
    public static final int SALT_LENGTH = 32;
    public static final int NONCE_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private VaultCrypto() {}

    /** Parámetros de Argon2id; se guardan en la cabecera del fichero. */
    public record KdfParams(int memoryKiB, int iterations, int parallelism) {
        public static final KdfParams DEFAULT = new KdfParams(64 * 1024, 3, 1);
        private static final int MAX_MEMORY_KIB = 1024 * 1024;
        private static final int MAX_ITERATIONS = 20;
        private static final int MAX_PARALLELISM = 16;

        public boolean isSane() {
            return memoryKiB >= 8 * parallelism && memoryKiB <= MAX_MEMORY_KIB
                    && iterations >= 1 && iterations <= MAX_ITERATIONS
                    && parallelism >= 1 && parallelism <= MAX_PARALLELISM;
        }
    }

    public static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    public static byte[] deriveKey(char[] password, byte[] salt, KdfParams params) {
        byte[] pwdBytes = toUtf8(password);
        try {
            Argon2Parameters argon = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withSalt(salt)
                    .withMemoryAsKB(params.memoryKiB())
                    .withIterations(params.iterations())
                    .withParallelism(params.parallelism())
                    .build();
            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(argon);
            byte[] key = new byte[KEY_LENGTH];
            generator.generateBytes(pwdBytes, key);
            return key;
        } finally {
            Arrays.fill(pwdBytes, (byte) 0);
        }
    }

    /** Devuelve nonce(12) + texto cifrado + tag. */
    public static byte[] encrypt(byte[] key, byte[] aad, byte[] plaintext) throws GeneralSecurityException {
        byte[] nonce = randomBytes(NONCE_LENGTH);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(aad);
        byte[] ciphertext = cipher.doFinal(plaintext);
        return ByteBuffer.allocate(NONCE_LENGTH + ciphertext.length).put(nonce).put(ciphertext).array();
    }

    public static byte[] decrypt(byte[] key, byte[] aad, byte[] nonceAndCiphertext) throws GeneralSecurityException {
        if (nonceAndCiphertext.length < NONCE_LENGTH + GCM_TAG_BITS / 8) {
            throw new AEADBadTagException("Datos cifrados demasiado cortos");
        }
        byte[] nonce = Arrays.copyOfRange(nonceAndCiphertext, 0, NONCE_LENGTH);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(nonceAndCiphertext, NONCE_LENGTH, nonceAndCiphertext.length - NONCE_LENGTH);
    }

    /** HKDF-SHA256 de 32 bytes. */
    public static byte[] hkdf(byte[] ikm, String info) {
        HKDFBytesGenerator g = new HKDFBytesGenerator(new SHA256Digest());
        g.init(new HKDFParameters(ikm, null, info.getBytes(StandardCharsets.UTF_8)));
        byte[] out = new byte[KEY_LENGTH];
        g.generateBytes(out, 0, out.length);
        return out;
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] toUtf8(char[] chars) {
        ByteBuffer buf = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        Arrays.fill(buf.array(), (byte) 0);
        return bytes;
    }
}
