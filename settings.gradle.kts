pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.architectury.dev")
        maven("https://maven.minecraftforge.net")
        maven("https://repo.essential.gg/repository/maven-public")
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases")
        maven("https://repo.spongepowered.org/repository/maven-public/")
    }
}

includeBuild("../essential-gradle-toolkit")
include("common")

rootProject.name = "KotlinHotReload"
rootProject.buildFileName = "root.gradle.kts"

val versionList = listOf(
    "26.2-fabric",
    "1.19.4-fabric",
    "1.14.4-fabric",

//    "26.2-neoforge",
//    "1.21.11-neoforge",
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
