plugins {
    alias(libs.plugins.kotlin.jvm)
}

version = property("mod_version") as String
group = property("mod_group") as String

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(libs.asm)
    compileOnly(libs.bundles.kotlin.compiler)
    compileOnly(libs.bundles.maven.resolver)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
}
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}
kotlin {
    jvmToolchain(25)
}
