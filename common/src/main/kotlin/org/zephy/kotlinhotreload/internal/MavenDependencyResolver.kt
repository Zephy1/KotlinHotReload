package org.zephy.kotlinhotreload.internal

import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.resolution.DependencyResolutionException
import org.eclipse.aether.supplier.RepositorySystemSupplier
import org.eclipse.aether.transfer.AbstractTransferListener
import org.eclipse.aether.transfer.TransferEvent
import java.io.File
import java.security.MessageDigest

private fun DependencyResolutionException.perArtifactReason(): String {
    val failures = (result.collectExceptions + result.artifactResults.flatMap { it.exceptions })
        .map { it.describe() }
        .distinct()
    return if (failures.isNotEmpty()) failures.joinToString("; ") else describe()
}

class MavenDependencyResolver(
    private val localRepoDir: File,
    private val remoteRepositories: List<RemoteRepository> = listOf(
        RemoteRepository.Builder(
            "central",
            "default",
            "https://repo.maven.apache.org/maven2/",
        ).build()
    ),
) {
    private val repositorySystem: RepositorySystem = RepositorySystemSupplier().get()

    init {
        localRepoDir.mkdirs()
    }

    fun resolve(coordinates: List<String>, cacheKeyDir: File): List<File> {
        if (coordinates.isEmpty()) return emptyList()

        val cacheFile = File(cacheKeyDir, "dependencies.cache")
        val hash = hashOf(coordinates)

        if (cacheFile.exists()) {
            val lines = cacheFile.readLines()
            if (lines.isNotEmpty() && lines[0] == hash) {
                val cachedJars = lines.drop(1).map(::File)
                if (cachedJars.isNotEmpty() && cachedJars.all { it.exists() }) {
                    return cachedJars
                }
            }
        }

        val session = newSession()
        val dependencies = coordinates.map { coord ->
            Dependency(DefaultArtifact(coord), "runtime")
        }

        val collectRequest = CollectRequest().apply {
            this.dependencies = dependencies
            this.repositories = remoteRepositories
        }

        val dependencyRequest = DependencyRequest(collectRequest, null)

        val result = try {
            repositorySystem.resolveDependencies(session, dependencyRequest)
        } catch (e: DependencyResolutionException) {
            throw IllegalStateException(
                "Failed to resolve dependencies [${coordinates.joinToString()}]: ${e.perArtifactReason()} (repositories tried: ${remoteRepositories.joinToString { it.url }})",
                e
            )
        }

        val jars = result.artifactResults.mapNotNull { it.artifact?.file }

        if (jars.size < coordinates.size) {
            System.err.println(
                "${ScriptEngine.LOG_PREFIX} Warning: requested ${coordinates.size} coordinate(s) but only resolved ${jars.size} jar(s) - some dependencies may have resolved without a file."
            )
        }

        cacheKeyDir.mkdirs()
        cacheFile.writeText((listOf(hash) + jars.map { it.absolutePath }).joinToString("\n"))

        return jars
    }

    private fun newSession(): RepositorySystemSession {
        val session = MavenRepositorySystemUtils.newSession()
        val localRepo = LocalRepository(localRepoDir)
        session.localRepositoryManager = repositorySystem.newLocalRepositoryManager(session, localRepo)
        session.transferListener = LoggingTransferListener()
        return session
    }

    private fun hashOf(coordinates: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        coordinates.sorted().forEach { digest.update(it.toByteArray()) }
        remoteRepositories.map { it.url }.sorted().forEach { digest.update(it.toByteArray()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class LoggingTransferListener : AbstractTransferListener() {
        override fun transferFailed(event: TransferEvent) {
            val reason = event.exception?.describe() ?: "unknown reason"
            System.err.println("${ScriptEngine.LOG_PREFIX} Download failed: ${event.resource.repositoryUrl}${event.resource.resourceName} - $reason")
        }

        override fun transferCorrupted(event: TransferEvent) {
            val reason = event.exception?.describe() ?: "unknown reason"
            System.err.println("${ScriptEngine.LOG_PREFIX} Corrupted download: ${event.resource.repositoryUrl}${event.resource.resourceName} - $reason")
        }
    }
}
