plugins {
    `java-library`
}

// Módulo compartido con el escritorio (formato, cripto, fusión y sincronización). Copia de sus fuentes en `core/src`
// (ver core/README.md): el repositorio es autocontenido y compila sin el cliente de escritorio.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    main { java.srcDir("src/main/java") }
    test {
        java.srcDir("src/test/java")
        resources.srcDir("src/test/resources")
    }
}

dependencies {
    api(libs.bouncycastle)
    api(libs.gson)
    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.withType<Test> { useJUnitPlatform() }
