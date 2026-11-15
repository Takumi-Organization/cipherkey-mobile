# CipherKey Mobile

Cliente Android del gestor de contraseñas CipherKey y servidor de sincronización cifrada de extremo a extremo.
El formato de bóveda, la criptografía, la fusión y el protocolo de sync viven en el módulo compartido
`../CipherKey-Rediseño/CipherKey/core` (Java 17), que Android compila como fuente (`ck.core.dir` en `android/gradle.properties`).

```
android/   app Kotlin + Jetpack Compose (módulos :app y :core)
server/    servidor Rust (axum + SQLite): guarda solo registros cifrados, firmas Ed25519, anti-repetición, revocación
```

## Estado (2026-11-14)

Funciona y está probado:
- Bóveda: crear, importar `.ckey` v2/v3, editar, favoritos, generador, cambiar contraseña maestra, exportar copia cifrada.
- Bloqueo por inactividad y al salir de la app, `FLAG_SECURE` configurable, desbloqueo con maestra.
- Sincronización con servidor propio (alta, emparejado por código, conflictos y su resolución, WorkManager).
- Autocompletado: servicio con autenticación a nivel de Dataset, emparejado estricto por dominio o `androidapp://<paquete>`.

Ver "Pruebas" para qué está verificado y dónde. Lo que depende de huella real se indica en la sección de pendientes.

## Compilar y probar

Android (siempre un solo Gradle a la vez en esta máquina):

```
cd android
./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx1g :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Servidor:

```
cd server
cargo test                       # 9 pruebas de integración (tests/api.rs); sin pruebas unitarias propias
CK_ADMIN_TOKEN=<token> CK_BIND=127.0.0.1:8787 cargo run --release
```

Variables del servidor: `CK_BIND`, `CK_DB`, `CK_ADMIN_TOKEN`, `CK_OPEN_REGISTRATION`, `CK_TRUST_PROXY`, `CK_TOMBSTONE_DAYS`, `CK_RATE_PER_MIN`.
Pon HTTPS delante con un proxy inverso. La app en debug permite HTTP solo hacia `10.0.2.2` (emulador).

Pruebas instrumentadas (dispositivo o emulador conectado; con servidor local arrancado):

```
ANDROID_SERIAL=<serie> ./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.ck.admin=<token>
```

Sin `ck.admin` se omiten las de sincronización. `AutofillAuthActivityTest` se omite si el dispositivo ya tiene una bóveda.
Tras terminar, AGP desinstala la app: se pierden sus datos locales.

## Pruebas

- JVM (`:app:testDebugUnitTest`): emparejado de autofill, clasificador de campos, política de contraseña maestra, generador.
- Instrumentadas: sincronización A, B y C entre dos dispositivos simulados, pantalla de conflictos, cambio de contraseña y exportar/importar,
  envoltura de clave con el Keystore (sin autenticación), `AutofillAuthActivity`.
- Servidor: `cargo test`.
- Escritorio (referencia, sin modificar): `mvn test` en `../CipherKey-Rediseño/CipherKey` da 61 pruebas correctas.

## Escenarios de sincronización (definidos aquí)

A: alta de la bóveda en el servidor, emparejado de un segundo dispositivo y convergencia (incluye borrado).
B: ediciones concurrentes de campos distintos, fusión automática sin conflicto.
C: ediciones concurrentes del mismo campo, conflicto con ambas versiones, resolución y convergencia.

## Sincronización en proximidad (misma red, sin servidor)

Con la bóveda **desbloqueada** (con o sin servidor configurado) la app escucha en `47890/tcp`, anuncia y descubre dispositivos por
multidifusión UDP (`47891`) y sincroniza directamente con el escritorio u otro móvil. **No hace falta ningún servidor.**
Diseño: `docs/adr/0002-proximidad-sin-servidor.md` del repositorio de escritorio (`CipherKey-Rediseño`); canal y descubrimiento en el 0001.

**Ajustes > «Proximidad (red local, sin servidor)»** (la sección del servidor queda aparte y es opcional):

1. En un dispositivo pulsa **Permitir vinculación** (2 min); en el otro **Vincular** (en la lista de cercanos) o **Vincular por dirección** (`IP:puerto`).
2. Compara el **código de 6 dígitos** en las dos pantallas y acepta en ambas.
3. La primera vez aparece un **resumen** («X entradas nuevas de este dispositivo, Y del otro, Z con contenido distinto») que hay
   que aceptar; antes se hace una copia de seguridad local (`vault.ckey.proximidad-<fecha>.bak`, cifrada, en el almacenamiento privado de la app).
4. **Sincronizar con cercanos**, **Sincronizar con la dirección** y **Desvincular** (corta la sincronización con ese dispositivo).

- Unión segura: la primera fusión entre dos bóvedas distintas nunca sustituye ni borra entradas; mismo identificador con contenido
  distinto queda como conflicto (pantalla de conflictos); una lápida ajena no borra una entrada viva.
- Si no arranca (puerto ocupado, sin red) Ajustes muestra el motivo y «Reintentar». Sin multidifusión se conecta por dirección.
- Solo vive con la app abierta y desbloqueada; usa `MulticastLock` (permisos `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`).
- Android 17 (target 37) exigirá `ACCESS_LOCAL_NETWORK`; hoy target 36 y no se declara (no verificado en un dispositivo con Android 17).
- **Verificado (2026-11-15):** `:core:test` (incluye `PeerSyncTest`, 23 pruebas, sobre el mismo núcleo), pruebas JVM y `assembleDebug`;
  prueba instrumentada `ProximityNoServerTest` en emulador (dos bóvedas temporales, sin servidor: vincular con código, resumen, copia,
  unión sin pérdida, código rechazado y desvincular); APK instalado en el móvil físico solo para comprobar que arranca (sin
  desbloquear su bóveda).
- **No verificado:** sincronización real móvil físico ↔ escritorio con las bóvedas reales (la hará Adrián); el descubrimiento por
  multidifusión entre dos máquinas reales (el emulador está tras NAT; allí se vincula por dirección con `adb forward`/`10.0.2.2`);
  Wi-Fi con aislamiento de clientes.

## Autocompletado

Activar en Ajustes de la app (o Ajustes del sistema > Autocompletado). El servicio siempre responde con una autenticación:
se desbloquea (maestra o huella) y se rellena el registro elegido. Solo se ofrecen registros que encajan:
- Web: dominio igual o padre en frontera de etiqueta; no se aceptan sufijos públicos ni una sola etiqueta como dominio padre;
  una página http no recibe credenciales guardadas como https.
- App: la URL del registro debe ser `androidapp://<paquete>` exacto.
Chrome no usa servicios de terceros por defecto; sí lo hacen las apps y las WebView (en `localhost` o https).

## Verificación en emulador (API 36, 2026-11-14)

Hecho en pantalla: crear bóveda, editar, ajustes, huella (activar con `BiometricPrompt`, desbloquear con ella y usarla en el autofill),
autofill con app y WebView de prueba (relleno, sin coincidencia, dominio parecido, rebaja https a http), sincronización desde la UI
(indicador "Todo sincronizado"), emparejado con código mostrado en pantalla y segundo dispositivo simulado con
`SecondDeviceTest` (`am instrument -e ck.pair <código>`), lista de dispositivos y revocación, exportar e importar `.ckey` con el selector del sistema.
La huella se enrola en el emulador con PIN + `BIOMETRIC_ENROLL` + `adb emu finger touch 1`.

Bugs que salieron de esa verificación y están corregidos: indicador de sync que no se apagaba, y cierre de la app al abrir el selector de archivos
(`biometric` arrastraba `fragment` 1.2.5: se fija 1.8.9 en `libs.versions.toml`).

## Límites conocidos

- Cambiar el servidor de sincronización exige desactivar y volver a activar.
- Importar una bóveda descarta la identidad de sincronización (queda como bóveda local; la anterior se aparta como `.bak`).
- Sin guardado de credenciales nuevas desde el autocompletado (`onSaveRequest` no hace nada).
- La lista de sufijos públicos del emparejador es mínima, no la Public Suffix List completa.
- Chrome no usa servicios de terceros por defecto; la prueba de autofill en navegador real no se ha hecho.
