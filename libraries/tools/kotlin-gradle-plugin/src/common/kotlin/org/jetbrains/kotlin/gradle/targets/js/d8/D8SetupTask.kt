package org.jetbrains.kotlin.gradle.targets.js.d8

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.jetbrains.kotlin.gradle.logging.kotlinInfo
import org.jetbrains.kotlin.gradle.utils.ArchiveOperationsCompat
import java.io.File
import javax.inject.Inject
import java.io.FileOutputStream
import java.nio.channels.Channels

abstract class D8SetupTask : DefaultTask() {
    @Transient
    private val settings = D8RootPlugin.apply(project.rootProject)
    private val env by lazy { settings.requireConfigured() }

    private val archiveOperations = ArchiveOperationsCompat(project)

    @get:Inject
    internal open val fs: FileSystemOperations
        get() = error("Should be injected")

    @Suppress("unused")
    val downloadUrl: String
        @Input get() = env.downloadUrl.toString()

    @Suppress("unused")
    val targetPath: String
        @OutputDirectory get() = env.targetPath.absolutePath

    @Suppress("unused")
    val zipPath: String
        @OutputFile get() = env.zipPath.absolutePath

    @Transient
    @get:Internal
    internal lateinit var configuration: Provider<Configuration>

    private fun downloadD8Zip(): File {
        if (!env.zipPath.exists()) {
            val readableByteChannel = Channels.newChannel(env.downloadUrl.openStream())
            env.zipPath.parentFile.mkdirs()
            env.zipPath.createNewFile()
            val fileOutputStream = FileOutputStream(zipPath)
            fileOutputStream.channel.transferFrom(readableByteChannel, 0, Long.MAX_VALUE)
        }
        return env.zipPath
    }

    @get:Classpath
    @get:Optional
    val d8Distribution: File? by lazy {
        if (settings.download) downloadD8Zip() else null
    }

    init {
        onlyIf {
            settings.download
        }
    }

    @TaskAction
    fun exec() {
        if (!settings.download) return
        if (env.zipPath.exists() && env.executablePath.exists()) return
        logger.kotlinInfo("Using node distribution from '$d8Distribution'")

        val downloadedZip = d8Distribution
        check(downloadedZip != null)
        check(env.zipPath.exists())

        if (env.executablePath.exists()) return
        env.targetPath.mkdirs()

        unpackNodeArchive(downloadedZip, env.targetPath)
        check(env.executablePath.exists())

        if (!env.isWindows) {
            env.executablePath.setExecutable(true)
        }
    }

    private fun unpackNodeArchive(archive: File, destination: File) {
        logger.kotlinInfo("Unpacking $archive to $destination")

        fs.copy {
            it.from(archiveOperations.zipTree(archive))
            it.into(destination)
        }
    }

    companion object {
        const val NAME: String = "kotlinD8Setup"
    }
}
