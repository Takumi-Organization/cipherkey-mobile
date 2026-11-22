# Módulo `core` (formato, criptografía, fusión y sincronización)

Es el módulo compartido con el cliente de escritorio de CipherKey (Java). Aquí se incluye una **copia de sus fuentes**
(`src/main` y `src/test`) tomada del cliente de escritorio en el commit `e7a0ef9` (rama `ryoku-glass`, versión 4.1.1;
licencia MIT, mismo autor), para que este repositorio compile solo, sin ningún repositorio hermano.

- Formato de bóveda, cifrado, fusión de registros y protocolo de sincronización por proximidad viven aquí.
- Se compila como módulo `:core` de Gradle (Java 17, BouncyCastle y Gson).
- Pruebas: `./gradlew :core:test` (formato y cripto, fusión, sincronización entre pares, fixtures de bóvedas antiguas).
- Si el formato cambia en el escritorio, hay que copiar de nuevo `src/` desde allí y anotar aquí el commit de origen.
