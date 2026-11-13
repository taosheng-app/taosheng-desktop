package app.resound.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class GreetingTest {
    @Test
    fun greetingIsAppName() {
        assertEquals("涛声桌面版", greeting())
    }
}