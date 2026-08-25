package org.zephy.kotlinhotreload.internal

import java.io.File

class IdeaProject(private val projectManager: ProjectManager) {
    private val jdkName = ScriptCompiler.JavaVersion.jvmVersion.replace("1.8", "8")
    private val sharedLibraryName = "MinecraftApi"
    private fun extraLibraryNameFor(projectName: String) = "Deps-$projectName"

    fun regenerate() {
        try {
            val projectNames = projectManager.listProjectNames()
            projectNames.forEach { name -> regenerateOne(name) }
        } catch (e: Exception) {
            System.err.println("${ScriptEngine.LOG_PREFIX} Failed to regenerate IDE project files: ${e.describe()}.")
        }
    }

    fun regenerateOne(projectName: String) {
        try {
            val extraJars = projectManager.extraIdeClasspathFor(projectName) ?: return

            val projectDir = projectManager.ideProjectDirFor(projectName)
            val ideaDir = File(projectDir, ".idea")
            val librariesDir = File(ideaDir, "libraries")
            librariesDir.mkdirs()

            writeLibraryXml(librariesDir, sharedLibraryName, projectManager.sharedIdeClasspath())

            val extraLibraryFile = File(librariesDir, "${extraLibraryNameFor(projectName)}.xml")
            if (extraJars.isNotEmpty()) {
                writeLibraryXml(librariesDir, extraLibraryNameFor(projectName), extraJars)
            } else {
                extraLibraryFile.delete()
            }

            writeModuleIml(projectDir, projectName, hasExtraDeps = extraJars.isNotEmpty())
            writeModulesXml(ideaDir, projectName)
            writeMiscXml(ideaDir)
        } catch (e: Exception) {
            System.err.println("${ScriptEngine.LOG_PREFIX} Failed to regenerate IDE project for '$projectName': ${e.describe()}.")
        }
    }

    private fun File.toIdeUrl(): String {
        val normalized = invariantSeparatorsPath
        return if (isDirectory) "file://$normalized/" else "jar://$normalized!/"
    }

    private fun writeLibraryXml(librariesDir: File, name: String, jars: List<File>) {
        val roots = jars.filter { it.exists() }.sortedBy { it.absolutePath.lowercase() }
        val classesXml = roots.joinToString("\n") { "      <root url=\"${it.toIdeUrl()}\" />" }
        val xml = """
            |<component name="libraryTable">
            |  <library name="$name">
            |    <CLASSES>
            |$classesXml
            |    </CLASSES>
            |    <JAVADOC />
            |    <SOURCES />
            |  </library>
            |</component>
        """.trimMargin()
        File(librariesDir, "$name.xml").writeText(xml)
    }

    private fun writeModuleIml(projectDir: File, name: String, hasExtraDeps: Boolean) {
        val extraOrderEntry = if (hasExtraDeps) {
            "\n    <orderEntry type=\"library\" level=\"project\" name=\"${extraLibraryNameFor(name)}\" />"
        } else ""
        val sourceUrl = $$"file://$MODULE_DIR$"
        val xml = """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<module type="JAVA_MODULE" version="4">
            |  <component name="NewModuleRootManager" inherit-compiler-output="true">
            |    <exclude-output />
            |    <content url="$sourceUrl">
            |      <sourceFolder url="$sourceUrl" isTestSource="false" />
            |    </content>
            |    <orderEntry type="inheritedJdk" />
            |    <orderEntry type="sourceFolder" forTests="false" />
            |    <orderEntry type="library" level="project" name="$sharedLibraryName" />$extraOrderEntry
            |  </component>
            |</module>
        """.trimMargin()
        File(projectDir, "$name.iml").writeText(xml)
    }

    private fun writeModulesXml(ideaDir: File, name: String) {
        val xml = $$"""
            |<?xml version="1.0" encoding="UTF-8"?>
            |<project version="4">
            |  <component name="ProjectModuleManager">
            |    <modules>
            |      <module fileurl="file://$PROJECT_DIR$/$$name.iml" filepath="$PROJECT_DIR$/$$name.iml" />
            |    </modules>
            |  </component>
            |</project>
        """.trimMargin()
        File(ideaDir, "modules.xml").writeText(xml)
    }

    private fun writeMiscXml(ideaDir: File) {
        val xml = """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<project version="4">
            |  <component name="ProjectRootManager" version="2" languageLevel="JDK_$jdkName" project-jdk-name="$jdkName" project-jdk-type="JavaSDK" />
            |</project>
        """.trimMargin()
        File(ideaDir, "misc.xml").writeText(xml)
    }
}
