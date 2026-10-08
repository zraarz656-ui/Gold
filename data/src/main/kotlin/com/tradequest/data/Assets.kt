package com.tradequest.data

import android.content.Context
import java.io.File
import java.io.InputStream

/** Abstraction over bundled assets so import logic is testable off-device. */
interface AssetSource {
    fun open(name: String): InputStream
}

/** Reads from the app's `assets/` folder. */
class AndroidAssetSource(private val context: Context) : AssetSource {
    override fun open(name: String): InputStream = context.assets.open(name)
}

/** Reads from a plain directory (used by JVM tests). */
class FileAssetSource(private val root: File) : AssetSource {
    override fun open(name: String): InputStream = File(root, name).inputStream()
}
