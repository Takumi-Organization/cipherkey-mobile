package dev.cipherkey.core;

import dev.cipherkey.core.format.VaultContainer;
import dev.cipherkey.core.model.V2Migrator;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

/** El fichero fixtures/v2-legacy.ckey lo escribió el código original de CipherKey 4.0.0 (Vault v2), no este módulo. */
class LegacyFixtureTest {
    @Test
    void coreOpensAVaultWrittenByTheOriginalDesktopCode() throws Exception {
        byte[] file;
        try (InputStream in = getClass().getResourceAsStream("/fixtures/v2-legacy.ckey")) { file = in.readAllBytes(); }
        assertEquals(2, VaultContainer.peekVersion(file));
        var opened = VaultContainer.open(file, "clave-de-prueba-v2".toCharArray());
        var doc = V2Migrator.migrate(opened.plaintext(), ZoneId.of("UTC"), "d");
        assertEquals(2, doc.records.size());
        assertEquals("GitHub", doc.records.get(0).title);
        assertEquals("gh-pass-1", doc.records.get(0).password);
        assertEquals("JBSWY3DPEHPK3PXP", doc.records.get(0).totpSecret);
        assertEquals("Banco ñandú", doc.records.get(1).title);
        assertEquals("p€2", doc.records.get(1).password);
        assertThrows(VaultContainer.WrongPasswordException.class, () -> VaultContainer.open(file, "mala".toCharArray()));
    }
}
