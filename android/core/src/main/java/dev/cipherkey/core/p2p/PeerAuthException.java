package dev.cipherkey.core.p2p;

/** Fallo de autenticación o de confianza con un par (no es un error de red). El mensaje no incluye secretos. */
public class PeerAuthException extends java.io.IOException {
    public PeerAuthException(String msg) { super(msg); }
}
