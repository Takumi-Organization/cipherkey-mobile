package dev.cipherkey.core.p2p;

import dev.cipherkey.core.model.VaultDocument;

/**
 * Acceso a la bóveda con su cerrojo. Cada llamada es corta (nunca se mantiene durante la red): así dos dispositivos que se
 * sincronizan a la vez no pueden bloquearse entre sí.
 */
public interface Locked {
    @FunctionalInterface
    interface Work<T> { T apply(VaultDocument doc) throws Exception; }

    <T> T run(Work<T> work) throws Exception;
}
