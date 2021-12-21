package com.huawei.utbot.instrumentation.examples.mock

import com.huawei.utbot.common.withRemovedFinalModifier
import com.huawei.utbot.framework.plugin.api.util.signature
import com.huawei.utbot.instrumentation.instrumentation.instrumenter.Instrumenter
import com.huawei.utbot.instrumentation.instrumentation.mock.MockClassVisitor
import com.huawei.utbot.instrumentation.instrumentation.mock.MockConfig
import java.lang.reflect.Method
import java.util.IdentityHashMap
import kotlin.reflect.jvm.javaMethod

/**
 * Helper for generating tests with methods mocks.
 */
class MockHelper(
    clazz: Class<*>
) {
    var mockClassVisitor: MockClassVisitor
    var instrumentedClazz: Class<*>

    private val memoryClassLoader = object : ClassLoader() {
        private val definitions: MutableMap<String, ByteArray> = mutableMapOf()

        fun addDefinition(name: String, bytes: ByteArray) {
            definitions[name] = bytes
        }

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            val bytes = definitions[name]
            return if (bytes != null) {
                defineClass(name, bytes, 0, bytes.size)
            } else {
                super.loadClass(name, resolve)
            }
        }
    }

    init {
        val instrumenter = Instrumenter(clazz)
        mockClassVisitor =
            instrumenter.visitClass { writer -> MockClassVisitor(writer, MockGetter::getMock.javaMethod!!) }

        memoryClassLoader.addDefinition(clazz.name, instrumenter.classByteCode)
        instrumentedClazz = memoryClassLoader.loadClass(clazz.name)
    }

    inline fun <reified T> withMockedMethod(method: Method, instance: Any?, mockedValues: List<*>, block: (Any?) -> T): T {
        if (method.returnType == Void.TYPE) {
            error("Can't mock function returning void!")
        }

        val sign = method.signature
        val methodId = mockClassVisitor.signatureToId[sign]

        val isMockField = instrumentedClazz.getDeclaredField(MockConfig.IS_MOCK_FIELD + methodId)
        MockGetter.updateMocks(instance, method, mockedValues)

        return isMockField.withRemovedFinalModifier {
            isMockField.set(instance, true)
            val res = block(instance)
            isMockField.set(instance, false)
            res
        }
    }

    object MockGetter {
        /**
         * Instance -> method -> list of values in the return order
         */
        data class MockContainer(private val values: List<*>) {
            private var ptr: Int = 0
            fun nextValue(): Any? = values[ptr++]
        }

        private val mocks = IdentityHashMap<Any?, MutableMap<String, MockContainer>>()


        /**
         * Returns the next value for mocked method with supplied [methodSignature] on an [obj] object.
         *
         * This function has only to be called from the instrumented bytecode everytime
         * we need a next value for a mocked method.
         */
        @JvmStatic
        fun getMock(obj: Any?, methodSignature: String): Any? =
            mocks[obj]?.get(methodSignature).let { container ->
                container ?: error("Can't get mock container for method [$obj\$$methodSignature]")
                container.nextValue()
            }

        fun updateMocks(obj: Any?, method: Method, values: List<*>) {
            val methodMocks = mocks.getOrPut(obj) { mutableMapOf() }
            methodMocks[method.signature] = MockContainer(values)
        }
    }
}