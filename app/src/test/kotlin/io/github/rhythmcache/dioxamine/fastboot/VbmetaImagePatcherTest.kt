package io.github.rhythmcache.dioxamine.fastboot

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VbmetaImagePatcherTest {
    @Test
    fun setsDisableVerityAndVerificationBitsWithoutChangingOtherBytes() {
        val image = ByteArray(4096) { (it % 251).toByte() }
        "AVB0".toByteArray(Charsets.US_ASCII).copyInto(image, 0)
        ByteBuffer.wrap(image).order(ByteOrder.BIG_ENDIAN).putInt(120, 0x10203040)
        val original = image.copyOf()
        val output = ByteArrayOutputStream()

        val result = VbmetaImagePatcher.patch(ByteArrayInputStream(image), output)
        val patched = output.toByteArray()

        assertEquals(image.size.toLong(), result.size)
        assertEquals(0x10203040, result.originalFlags)
        assertEquals(0x10203043, result.patchedFlags)
        assertEquals(0x10203043, ByteBuffer.wrap(patched).order(ByteOrder.BIG_ENDIAN).getInt(120))
        original[123] = (original[123].toInt() or 0x03).toByte()
        assertArrayEquals(original, patched)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsImageWithoutAvbMagic() {
        VbmetaImagePatcher.patch(ByteArrayInputStream(ByteArray(512)), ByteArrayOutputStream())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedHeader() {
        VbmetaImagePatcher.patch(ByteArrayInputStream(byteArrayOf(0x41, 0x56, 0x42, 0x30)), ByteArrayOutputStream())
    }
}
