plugins {
    alias(libs.plugins.platform)
    alias(libs.plugins.kotlin)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

group = "org.tera201"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(libs.commons.io)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    implementation(libs.commons.lang3)
    implementation(libs.slf4j.simple)
    implementation(libs.jgit) {
        exclude(group = "org.slf4j")
    }
    implementation(libs.sqlite)
    implementation(libs.jackson.databind)
    implementation(libs.coroutines.core)
    implementation(libs.bundles.exposed)

    intellijPlatform {
        intellijIdeaCommunity("2025.1")
    }
}