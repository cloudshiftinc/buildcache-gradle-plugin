package io.cloudshiftdev.gradle.buildcache.util

import java.io.FilterInputStream
import java.io.InputStream

internal class CountingInputStream(inputStream: InputStream) : FilterInputStream(inputStream) {
    private var bytesRead: Long = 0
    internal val count: Long
        get() = bytesRead

    override fun read(): Int {
        val result = super.read()
        if (result != -1) {
            bytesRead++
        }
        return result
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val result = super.read(b, off, len)
        if (result != -1) {
            bytesRead += result
        }
        return result
    }
}
