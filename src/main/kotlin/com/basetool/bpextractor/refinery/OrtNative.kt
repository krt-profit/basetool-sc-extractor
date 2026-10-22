package com.basetool.bpextractor.refinery

import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Where ONNX Runtime loads its native libraries from, and the clean-up of the copies it unpacks.
 * Without a library path, ONNX Runtime unpacks its DLLs into a fresh `onnxruntime-java…` folder in the
 * temp folder on every start and cannot delete it on exit, because Windows keeps a loaded DLL locked.
 */
object OrtNative {

    /** The system property ONNX Runtime loads its libraries from instead of unpacking them. */
    const val NATIVE_PATH_PROPERTY = "onnxruntime.native.path"

    /** The folder the packaged app's resources live in, set by the app image's launcher. */
    const val RESOURCES_DIR_PROPERTY = "compose.application.resources.dir"

    /** The libraries ONNX Runtime needs; the app image carries them in its resources folder. */
    val LIBRARIES = listOf("onnxruntime.dll", "onnxruntime4j_jni.dll")

    /** The name prefix of the folders ONNX Runtime unpacks into. */
    const val EXTRACT_PREFIX = "onnxruntime-java"

    /** How long an unpacked copy must have been left alone before [sweepStaleExtractions] removes it. */
    val STALE_AFTER: Duration = Duration.ofDays(1)

    /**
     * Points ONNX Runtime at the libraries in [resourcesDir] when they are all there and no path is set
     * yet; call before the first ONNX Runtime class loads. Returns the folder it set, or `null`.
     */
    fun useBundledLibraries(resourcesDir: String? = System.getProperty(RESOURCES_DIR_PROPERTY)): File? {
        if (System.getProperty(NATIVE_PATH_PROPERTY) != null) return null
        val dir = resourcesDir?.let(::File)?.takeIf { d -> LIBRARIES.all { File(d, it).isFile } } ?: return null
        System.setProperty(NATIVE_PATH_PROPERTY, dir.absolutePath)
        return dir
    }

    /**
     * Deletes the `onnxruntime-java…` folders in [tempRoot] last changed more than [STALE_AFTER] before
     * [now]. A folder whose DLL is still loaded resists and stays. Returns how many went; never throws.
     */
    fun sweepStaleExtractions(
        tempRoot: File = File(System.getProperty("java.io.tmpdir")),
        now: Instant = Instant.now(),
    ): Int = runCatching {
        val cutoff = now.minus(STALE_AFTER).toEpochMilli()
        tempRoot.listFiles { f -> f.isDirectory && f.name.startsWith(EXTRACT_PREFIX) && f.lastModified() < cutoff }
            ?.count { dir -> dir.deleteRecursively() }
            ?: 0
    }.getOrDefault(0)
}
