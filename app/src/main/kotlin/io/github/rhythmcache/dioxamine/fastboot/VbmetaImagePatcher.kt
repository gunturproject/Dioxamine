package io.github.rhythmcache.dioxamine.fastboot

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Rewrites the AVB vbmeta flags exactly as AOSP fastboot does for its disable flags. */
internal object VbmetaImagePatcher {
    private const val HEADER_SIZE = 256
    private const val FLAGS_OFFSET = 120
    private const val DISABLE_VERITY = 0x01
    private const val DISABLE_VERIFICATION = 0x02
    private const val MAX_IMAGE_SIZE = 64L * 1024L * 1024L
    private val AVB_MAGIC = byteArrayOf('A'.code.toByte(), 'V'.code.toByte(), 'B'.code.toByte(), '0'.code.toByte())

    data class PatchResult(val size: Long, val originalFlags: Int, val patchedFlags: Int)

    /** Writes a modified copy. The source is never changed; callers own and close both streams. */
    fun patch(source: InputStream, destination: OutputStream): PatchResult {
        val header = ByteArray(HEADER_SIZE)
        var headerBytes = 0
        while (headerBytes < header.size) {
            val count = source.read(header, headerBytes, header.size - headerBytes)
            if (count < 0) {
                throw IllegalArgumentException("vbmeta image is shorter than the 256-byte AVB header.")
            }
            if (count == 0) continue
            headerBytes += count
        }
        if (!header.copyOfRange(0, AVB_MAGIC.size).contentEquals(AVB_MAGIC)) {
            throw IllegalArgumentException("Selected image is not a standalone AVB vbmeta image (AVB0 magic not found).")
        }

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val originalFlags = buffer.getInt(FLAGS_OFFSET)
        val patchedFlags = originalFlags or DISABLE_VERITY or DISABLE_VERIFICATION
        buffer.putInt(FLAGS_OFFSET, patchedFlags)
        destination.write(header)

        var total = HEADER_SIZE.toLong()
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val count = source.read(chunk)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > MAX_IMAGE_SIZE) {
                throw IllegalArgumentException("vbmeta image exceeds the 64 MiB safety limit.")
            }
            destination.write(chunk, 0, count)
        }
        destination.flush()
        return PatchResult(total, originalFlags, patchedFlags)
    }
}
