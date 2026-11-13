plugins {
    `java-library`
}

// Módulo compartido con el escritorio: se compila desde sus fuentes originales (una sola copia del formato y del protocolo).
val coreDir = rootProject.file(providers.gradleProperty("ck.core.dir").get())

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    main { java.srcDir(File(coreDir, "src/main/java")) }
    test {
        java.srcDir(File(coreDir, "src/test/java"))
        resources.srcDir(File(coreDir, "src/test/resources"))
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
