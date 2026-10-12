package com.tradequest.data

import android.content.Context
import java.io.File
import java.io.InputStream

/** Abstraction over bundled assets so import logic is testable off-device. */
interface AssetSource {
    fun open(name: String): InputStream

    /** True when [name] is present in the bundle. Default assumes presence. */
    fun exists(name: String): Boolean = true

    /** The asset's byte size, or -1 when unknown. Used only for the Data panel. */
    fun size(name: String): Long = -1L
}

/** Reads from the app's `assets/` folder. */
class AndroidAssetSource(private val context: Context) : AssetSource {
    override fun open(name: String): InputStream = context.assets.open(name)

    override fun exists(name: String): Boolean =
        runCatching { context.assets.open(name).close() }.isSuccess

    override fun size(name: String): Long =
        runCatching { context.assets.openFd(name).use { it.length } }.getOrDefault(-1L)
}

/** Reads from a plain directory (used by JVM tests). */
class FileAssetSource(private val root: File) : AssetSource {
    override fun open(name: String): InputStream = File(root, name).inputStream()

    override fun exists(name: String): Boolean = File(root, name).isFile

    override fun size(name: String): Long = File(root, name).length()
}
