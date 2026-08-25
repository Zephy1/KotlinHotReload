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
    private val repositorySystem: RepositorySystem = newRepositorySystem()

    init {
        localRepoDir.mkdirs()
    }

    fun resolve(coordinates: List<String>, cacheKeyDir: File): List<File> {
        if (coordinates.isEmpty()) return emptyList()

        val cacheFile = File(cacheKeyDir, "dependencies.cache")
        val hash = hashOf(coordinates)

        readCachedJars(cacheFile, hash)?.let { return it }

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
            System.err.println("${ScriptEngine.LOG_PREFIX} Warning: requested ${coordinates.size} coordinate(s) but only resolved ${jars.size} jar(s) - some dependencies may have resolved without a file.")
        }

        writeCache(cacheFile, hash, jars)

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
        coordinates.sorted().forEach(digest::updateUtf8)
        remoteRepositories.map { it.url }.sorted().forEach(digest::updateUtf8)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readCachedJars(cacheFile: File, expectedHash: String): List<File>? {
        if (!cacheFile.isFile) return null

        val lines = cacheFile.readLines()
        if (lines.isEmpty() || lines[0] != expectedHash) return null

        val cachedJars = lines.drop(1).map(::File)
        return if (cachedJars.isNotEmpty() && cachedJars.all { it.exists() }) cachedJars else null
    }

    private fun writeCache(cacheFile: File, hash: String, jars: List<File>) {
        cacheFile.parentFile?.mkdirs()
        cacheFile.writeText((listOf(hash) + jars.map { it.absolutePath }).joinToString("\n"))
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

private fun newRepositorySystem(): RepositorySystem {
    val supplierAttempt = trySupplierApi()
    supplierAttempt.system?.let { return it }

    val locatorAttempt = tryServiceLocatorApi()
    locatorAttempt.system?.let { return it }

    error(
        "Could not construct a Maven RepositorySystem.\n" +
            "  RepositorySystemSupplier attempt: ${supplierAttempt.describeFailure()}\n" +
            "  DefaultServiceLocator attempt: ${locatorAttempt.describeFailure()}"
    )
}

private class ApiAttempt(val system: RepositorySystem?, val failure: Throwable?) {
    fun describeFailure(): String =
        if (system != null) "succeeded"
        else failure?.let { "${it.javaClass.name}: ${it.message}" } ?: "failed with no exception captured"
}

private fun trySupplierApi(): ApiAttempt {
    return try {
        val supplierClass = Class.forName("org.eclipse.aether.supplier.RepositorySystemSupplier")
        val supplier = supplierClass.getDeclaredConstructor().newInstance()
        val system = supplierClass.getMethod("get").invoke(supplier) as? RepositorySystem
        ApiAttempt(system, if (system == null) IllegalStateException("get() returned null or a non-RepositorySystem value") else null)
    } catch (e: ReflectiveOperationException) {
        ApiAttempt(null, e)
    } catch (e: LinkageError) {
        ApiAttempt(null, e)
    }
}

private fun tryServiceLocatorApi(): ApiAttempt {
    return try {
        val locator = MavenRepositorySystemUtils::class.java
            .getMethod("newServiceLocator")
            .invoke(null)
        val locatorClass = locator.javaClass
        val addService = locatorClass.getMethod("addService", Class::class.java, Class::class.java)

        val repositorySystemInterface = Class.forName("org.eclipse.aether.RepositorySystem")
        val defaultRepositorySystemImpl = Class.forName("org.eclipse.aether.internal.impl.DefaultRepositorySystem")
        addService.invoke(locator, repositorySystemInterface, defaultRepositorySystemImpl)

        val repoConnectorFactory = Class.forName("org.eclipse.aether.spi.connector.RepositoryConnectorFactory")
        val transporterFactory = Class.forName("org.eclipse.aether.spi.connector.transport.TransporterFactory")
        val basicConnectorFactory = Class.forName("org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory")
        val fileTransporterFactory = Class.forName("org.eclipse.aether.transport.file.FileTransporterFactory")
        val httpTransporterFactory = Class.forName("org.eclipse.aether.transport.http.HttpTransporterFactory")

        addService.invoke(locator, repoConnectorFactory, basicConnectorFactory)
        addService.invoke(locator, transporterFactory, fileTransporterFactory)
        addService.invoke(locator, transporterFactory, httpTransporterFactory)

        val getService = locatorClass.getMethod("getService", Class::class.java)

        val collaboratorInterfaceNames = listOf(
            "org.eclipse.aether.impl.VersionResolver",
            "org.eclipse.aether.impl.VersionRangeResolver",
            "org.eclipse.aether.impl.ArtifactResolver",
            "org.eclipse.aether.impl.MetadataResolver",
            "org.eclipse.aether.impl.ArtifactDescriptorReader",
            "org.eclipse.aether.impl.DependencyCollector",
            "org.eclipse.aether.impl.Installer",
            "org.eclipse.aether.impl.Deployer",
            "org.eclipse.aether.impl.LocalRepositoryProvider",
            "org.eclipse.aether.impl.SyncContextFactory",
            "org.eclipse.aether.impl.RemoteRepositoryManager",
            "org.eclipse.aether.impl.RepositoryConnectorProvider",
            "org.eclipse.aether.impl.RepositoryEventDispatcher",
            "org.eclipse.aether.impl.RepositoryLayoutProvider",
            "org.eclipse.aether.impl.OfflineController",
            "org.eclipse.aether.impl.UpdateCheckManager",
            "org.eclipse.aether.impl.UpdatePolicyAnalyzer",
            "org.eclipse.aether.impl.RepositorySystemLifecycle",
            "org.eclipse.aether.impl.RemoteRepositoryFilterManager",
        )
        val collaboratorResults = collaboratorInterfaceNames.map { name ->
            val status = try {
                val iface = Class.forName(name)
                val result = getService.invoke(locator, iface)
                if (result != null) "OK (${result.javaClass.name})" else "NULL - failed to construct, no exception surfaced"
            } catch (_: ClassNotFoundException) {
                "not present in this resolver version (skipped)"
            } catch (e: Throwable) {
                "threw ${e.javaClass.name}: ${e.message}"
            }
            "$name -> $status"
        }

        val system = getService.invoke(locator, RepositorySystem::class.java) as? RepositorySystem
        ApiAttempt(
            system,
            if (system == null) {
                IllegalStateException("getService(RepositorySystem) returned null after registering DefaultRepositorySystem:\n${collaboratorResults.joinToString("\n    ")}")
            } else null,
        )
    } catch (e: ClassNotFoundException) {
        ApiAttempt(
            null,
            IllegalStateException("${e.message} is not on the runtime classpath.", e),
        )
    } catch (e: ReflectiveOperationException) {
        ApiAttempt(null, e)
    } catch (e: LinkageError) {
        ApiAttempt(null, e)
    }
}
