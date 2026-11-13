package com.taosheng.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class GreetingTest {
    @Test
    fun greetingIsHelloWorld() {
        assertEquals("Hello, World!", greeting())
    }
}