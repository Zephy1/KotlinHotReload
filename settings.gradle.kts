pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.architectury.dev")
        maven("https://maven.minecraftforge.net")
        maven("https://repo.essential.gg/repository/maven-public")
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        maven("https://repo.spongepowered.org/repository/maven-public/")
    }
}

includeBuild("../essential-gradle-toolkit")
include("common")

rootProject.name = "KotlinHotReload"
rootProject.buildFileName = "root.gradle.kts"

val versionList = listOf(
    "26.2-fabric",
    "1.21.11-fabric",
    "1.21.10-fabric",
    "1.19.4-fabric",
    "1.18.2-fabric",
    "1.15.2-fabric",

//    "26.2-neoforge",
)
versionList.forEach { version ->
    file("versions/$version").mkdirs()
}

versionList.forEach { version ->
    include(":$version")
    project(":$version").apply {
        projectDir = file("versions/$version")
        buildFileName = "../../build.gradle.kts"
    }
}
