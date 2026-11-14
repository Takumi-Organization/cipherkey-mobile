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

## Autocompletado

Activar en Ajustes de la app (o Ajustes del sistema > Autocompletado). El servicio siempre responde con una autenticación:
se desbloquea (maestra o huella) y se rellena el registro elegido. Solo se ofrecen registros que encajan:
- Web: dominio igual o padre en frontera de etiqueta; no se aceptan sufijos públicos ni una sola etiqueta como dominio padre;
  una página http no recibe credenciales guardadas como https.
- App: la URL del registro debe ser `androidapp://<paquete>` exacto.
Chrome no usa servicios de terceros por defecto; sí lo hacen las apps y las WebView (en `localhost` o https).

## Pendientes y límites conocidos

- Sin icono propio de ajustes para cambiar el servidor de sync una vez activada (hay que desactivar y volver a activar).
- La huella solo se ha probado donde se indica en el informe de la sesión; el flujo con `BiometricPrompt` necesita un dispositivo con huella enrolada.
- Sin guardado de credenciales nuevas desde el autocompletado (`onSaveRequest` no hace nada).
- La lista de sufijos públicos del emparejador es mínima, no la Public Suffix List completa.
