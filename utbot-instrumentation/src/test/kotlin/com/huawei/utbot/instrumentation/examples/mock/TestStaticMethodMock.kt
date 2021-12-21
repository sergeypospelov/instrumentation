package com.huawei.utbot.instrumentation.examples.mock

import com.huawei.utbot.instrumentation.samples.mock.ClassForMockStaticMethods
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TestStaticMethodMock {
    private fun testMethod(methodName: String, mockedValues: List<*>) {
        val mockHelper = MockHelper(ClassForMockStaticMethods::class.java)
        val instrumentedClazz = mockHelper.instrumentedClazz
        val instance = null
        val method = instrumentedClazz.declaredMethods.first { it.name == methodName }
        method.isAccessible = true

        mockHelper.withMockedMethod(method, instance, mockedValues) {
            mockedValues.forEach {
                Assertions.assertEquals(it, method.invoke(instance))
            }
        }
    }

    @Test
    fun testInt() {
        testMethod("testI", listOf(0, 0, 1, 10, -20))
    }

    @Test
    fun testLong() {
        testMethod("testL", listOf(0L, 1L, -1L, 1337L))
    }

    @Test
    fun testObject() {
        testMethod(
            "testObject",
            listOf(ClassForMockStaticMethods.SomeClass2(1337), ClassForMockStaticMethods.SomeClass2(228))
        )
    }

    @Test
    fun testChar() {
        testMethod("testChar", listOf('a', 'Z', 0.toChar(), 255.toChar(), '-', '\n'))
    }

    @Test
    fun testFloat() {
        testMethod("testFloat", listOf(1.0f, -1.0f))
    }

    @Test
    fun testByte() {
        testMethod("testByte", listOf(1.toByte(), (-1).toByte()))
    }

    @Test
    fun testDouble() {
        testMethod("testDouble", listOf(1.0, -1.0, 1337.0))
    }

    @Test
    fun testArray() {
        testMethod("testArray", listOf(intArrayOf(1, 2, 3), intArrayOf(-1, -2, -3)))
    }

    @Test
    fun testShort() {
        testMethod("testShort", listOf(1.toShort(), 2.toShort(), 3.toShort()))
    }

    @Test
    fun testBoolean() {
        testMethod("testBoolean", listOf(false, true, true, false, false))
    }

    @Test
    fun testVoid() {
        assertThrows<IllegalStateException> {
            testMethod(
                "testVoid",
                listOf(null, "Kek", 1337)
            ) // we don't have to instrument function returning void at any case
        }
    }
}
