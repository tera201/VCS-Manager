plugins {
    kotlin("jvm") version "2.1.0"
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

group = "org.tera201"
version = "1.0.0"

repositories {
    mavenCentral()
}

val commonsVersion = "2.14.0"
val lombokVersion = "1.18.30"
val lang3Version = "3.3.2"
val slf4jVersion = "1.7.36"
val jgitVersion = "7.1.0.202411261347-r"
val sqliteVersion = "3.49.1.0"
val jacksonVersion = "2.15.1"
val coroutineVersion = "1.10.1"
val exposedVersion = "1.0.0-rc-2"

dependencies {
    implementation("commons-io:commons-io:$commonsVersion")
    compileOnly("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("org.projectlombok:lombok:$lombokVersion")
    implementation("org.apache.commons:commons-lang3:$lang3Version")
    implementation("org.slf4j:slf4j-simple:$slf4jVersion")
    implementation("org.eclipse.jgit:org.eclipse.jgit:$jgitVersion"){
        exclude(group = "org.slf4j")
    }
    implementation("org.xerial:sqlite-jdbc:$sqliteVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutineVersion")
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:${exposedVersion}")
}