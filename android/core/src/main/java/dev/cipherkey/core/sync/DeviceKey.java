package dev.cipherkey.core.sync;

import dev.cipherkey.core.crypto.VaultCrypto;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** Par de claves Ed25519 de un dispositivo y firma de peticiones. */
public final class DeviceKey {
    private final Ed25519PrivateKeyParameters priv;

    private DeviceKey(Ed25519PrivateKeyParameters priv) { this.priv = priv; }

    public static DeviceKey generate() { return new DeviceKey(new Ed25519PrivateKeyParameters(new SecureRandom())); }

    public static DeviceKey fromPrivateBase64(String b64) {
        return new DeviceKey(new Ed25519PrivateKeyParameters(Base64.getDecoder().decode(b64), 0));
    }

    public String privateBase64() { return Base64.getEncoder().encodeToString(priv.getEncoded()); }

    public String publicBase64() { return Base64.getEncoder().encodeToString(priv.generatePublicKey().getEncoded()); }

    public byte[] sign(byte[] msg) {
        Ed25519Signer s = new Ed25519Signer();
        s.init(true, priv);
        s.update(msg, 0, msg.length);
        return s.generateSignature();
    }

    public static boolean verify(String publicB64, byte[] msg, byte[] sig) {
        try {
            Ed25519Signer s = new Ed25519Signer();
            s.init(false, new Ed25519PublicKeyParameters(Base64.getDecoder().decode(publicB64), 0));
            s.update(msg, 0, msg.length);
            return s.verifySignature(sig);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Cadena que se firma: método, ruta con query, marca de tiempo, nonce y hash del cuerpo. */
    public static byte[] canonical(String method, String pathAndQuery, long ts, String nonce, byte[] body) {
        String hex = hex(VaultCrypto.sha256(body));
        return (method + "\n" + pathAndQuery + "\n" + ts + "\n" + nonce + "\n" + hex).getBytes(StandardCharsets.UTF_8);
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
