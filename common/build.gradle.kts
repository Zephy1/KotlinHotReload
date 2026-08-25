import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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
}

val supportedTargets = listOf(8, 17, 21, 25)
tasks.named<KotlinCompile>("compileKotlin") {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
}
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
kotlin {
    jvmToolchain(8)
}

supportedTargets.filter { it != 8 }.forEach { target ->
    val ss = sourceSets.create("jvm$target") {
        kotlin.srcDir(sourceSets.main.get().kotlin.srcDirs)
    }
    configurations.named(ss.compileOnlyConfigurationName) {
        extendsFrom(configurations.getByName("compileOnly"))
    }
    tasks.named<KotlinCompile>(ss.getCompileTaskName("kotlin")) {
        compilerOptions.jvmTarget.set(JvmTarget.fromTarget(target.toString()))
    }

    tasks.named<JavaCompile>(ss.compileJavaTaskName) {
        sourceCompatibility = target.toString()
        targetCompatibility = target.toString()
    }
}

supportedTargets.forEach { target ->
    val compileTaskName = if (target == 8) "compileKotlin" else "compileJvm${target}Kotlin"
    configurations.create("jvm${target}Elements") {
        isCanBeConsumed = true
        isCanBeResolved = false
        outgoing.artifact(tasks.named<KotlinCompile>(compileTaskName).flatMap { it.destinationDirectory }) {
            builtBy(tasks.named(compileTaskName))
        }
    }
}

supportedTargets.forEach { target ->
    val bundle = if (target <= 8) libs.bundles.maven.resolver.jvm8 else libs.bundles.maven.resolver
    val configName = if (target == 8) "compileOnly" else "jvm${target}CompileOnly"
    dependencies.add(configName, bundle)
}
