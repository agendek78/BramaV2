plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    implementation(libs.bouncycastle.prov)
    testImplementation(libs.junit)
}

// Generacja wektorów interop EC-JPAKE (src/test/resources/ecjpake/README.md).
tasks.test {
    project.findProperty("ecjpakeGen")?.let { systemProperty("ecjpake.gen", it) }
    // Generacja scenariuszy dla firmware (src/test/resources/vectors/README.md).
    project.findProperty("vectorsGen")?.let { systemProperty("vectors.gen", it) }
}
