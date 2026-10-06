package eu.kanade.tachiyomi.data.translation.offline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OcrTextFilterTest {

    @Test
    fun `lines are joined with spaces, or without for Japanese and Chinese`() {
        assertEquals("I can't believe it", OcrTextFilter.join(listOf("I can't", " believe it "), "en"))
        assertEquals("信じられない", OcrTextFilter.join(listOf("信じ", "られない"), "ja"))
        assertEquals("안녕 하세요", OcrTextFilter.join(listOf("안녕", "하세요"), "ko"))
    }

    @Test
    fun `all caps text becomes sentence case`() {
        assertEquals("I don't know. What is that?", OcrTextFilter.normalizeCase("I DON'T KNOW. WHAT IS THAT?", "en"))
        assertEquals("Where is it? I'm here", OcrTextFilter.normalizeCase("WHERE IS IT? I'M HERE", "en"))
    }

    @Test
    fun `lone i becomes capital i`() {
        assertEquals("Maybe I should go", OcrTextFilter.normalizeCase("MAYBE I SHOULD GO", "en"))
    }

    @Test
    fun `mixed case and other scripts are left alone`() {
        assertEquals("Hello There", OcrTextFilter.normalizeCase("Hello There", "en"))
        assertEquals("ABC", OcrTextFilter.normalizeCase("ABC", "en"))
        assertEquals("ありがとう", OcrTextFilter.normalizeCase("ありがとう", "ja"))
        assertEquals("HELLO THERE", OcrTextFilter.normalizeCase("HELLO THERE", "ko"))
    }

    @Test
    fun `noise is not translatable`() {
        assertFalse(OcrTextFilter.isTranslatable("12", "en"))
        assertFalse(OcrTextFilter.isTranslatable("...!?", "en"))
        assertFalse(OcrTextFilter.isTranslatable("A", "en"))
        assertFalse(OcrTextFilter.isTranslatable("READ AT WWW.EXAMPLE.COM", "en"))
        assertFalse(OcrTextFilter.isTranslatable("discord.gg/abc", "en"))
    }

    @Test
    fun `real text is translatable`() {
        assertTrue(OcrTextFilter.isTranslatable("Hello!", "en"))
        assertTrue(OcrTextFilter.isTranslatable("안녕하세요", "ko"))
        assertTrue(OcrTextFilter.isTranslatable("死", "ja"))
        assertTrue(OcrTextFilter.isTranslatable("你好", "zh"))
    }
}
