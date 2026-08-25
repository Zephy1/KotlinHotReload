plugins {
    alias(libs.plugins.kotlin.jvm)
    id("gg.essential.multi-version")
    id("gg.essential.defaults")
}

version = property("mod_version") as String
group = property("mod_group") as String

val commonTarget = platform.javaVersion.majorVersion.toInt()
    .let { if (it in listOf(8, 17, 21, 25)) it else 8 }

base {
    archivesName.set(property("mod_name") as String)
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(project(":common"))
    include(libs.bundles.kotlin.compiler)
    include(if (commonTarget <= 8) libs.bundles.maven.resolver.jvm8 else libs.bundles.maven.resolver)
}

tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand(mutableMapOf("version" to project.version))
    }
    val javaVersion = project.java.toolchain.languageVersion.get().asInt()
    inputs.property("compatibilityLevel", javaVersion)
    filesMatching("kotlinhotreload.mixins.json") {
        expand(mutableMapOf("compatibilityLevel" to javaVersion))
    }

    filesMatching("META-INF/neoforge.mods.toml") {
        expand(mutableMapOf("version" to project.version))
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions.jvmTarget.set(platform.jvmTarget)
}
java {
    sourceCompatibility = platform.javaVersion
    targetCompatibility = platform.javaVersion
}
kotlin {
    jvmToolchain(platform.javaVersion.majorVersion.toInt())
}

sourceSets {
    main {
        java.srcDir("src/main/java")
        kotlin.srcDir("src/main/kotlin")
        resources.srcDir("src/main/resources")
    }
}

tasks.jar {
    from(project(":common").configurations.named("jvm${commonTarget}Elements").map { it.artifacts.files })
}

afterEvaluate {
    val hasRemapJar = tasks.findByName("remapJar") != null
    val outputTaskName = if (hasRemapJar) "remapJar" else "jar"

    tasks.register<Copy>("collectJars") {
        group = "build"
        description = "Copies this version's jar to /jars"

        val outputDir = projectDir.resolve("../../jars").normalize()
        dependsOn(outputTaskName)

        from(tasks.named(outputTaskName)) {
            include("*.jar")
            exclude { it.name.contains(" 1.2") && it.name.contains("-all") }
            rename {
                "${rootProject.name}-${version}+${project.name}.jar"
            }
        }
        into(outputDir)
    }

    tasks.named("build") {
        finalizedBy("collectJars")
    }

    configurations.named("default") {
        isCanBeConsumed = true
        isCanBeResolved = false
    }

    artifacts {
        add("default", tasks.named(outputTaskName))
    }
}
