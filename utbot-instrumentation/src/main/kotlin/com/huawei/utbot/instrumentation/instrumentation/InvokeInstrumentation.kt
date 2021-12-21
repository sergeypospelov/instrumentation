package com.huawei.utbot.instrumentation.instrumentation

import com.huawei.utbot.common.withAccessibility
import com.huawei.utbot.framework.plugin.api.util.signature
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.security.ProtectionDomain

typealias ArgumentList = List<Any?>

/**
 * This instrumentation just invokes a given function and wraps result in [Result].
 */
class InvokeInstrumentation : Instrumentation<Result<*>> {
    /**
     * Invokes a method with the given [methodSignature], the declaring class of which is [clazz], with the supplied
     * [arguments] and [parameters], but [parameters] are just ignored.
     *
     * If it is instance method, `this` should be the first element of [arguments].
     *
     * @return `Result.success` with wrapped result in case of successful call and
     * `Result.failure` with wrapped target exception otherwise.
     */
    override fun invoke(
        clazz: Class<*>,
        methodSignature: String,
        arguments: ArgumentList,
        parameters: Any?
    ): Result<*> {
        val methodOrConstructor =
            (clazz.methods + clazz.declaredMethods).toSet().firstOrNull { it.signature == methodSignature }
                ?: clazz.declaredConstructors.firstOrNull { it.signature == methodSignature }
                ?: throw NoSuchMethodException("Signature: $methodSignature")

        val isStaticExecutable = Modifier.isStatic(methodOrConstructor.modifiers)

        val (thisObject, realArgs) = if (isStaticExecutable || methodOrConstructor is Constructor<*>) {
            null to arguments
        } else {
            arguments.firstOrNull()
                ?.let { it to arguments.drop(1) }
                ?: throw IllegalArgumentException("Wrong number of arguments.")
        }

        return try {
            when (methodOrConstructor) {
                is Method -> Result.success(methodOrConstructor.run {
                    withAccessibility {
                        invoke(thisObject, *realArgs.toTypedArray()).let {
                            if (returnType != Void.TYPE) it else Unit
                        } // invocation on method returning void will return null, so we replace it with Unit
                    }
                })
                is Constructor<*> -> Result.success(methodOrConstructor.run {
                    withAccessibility { newInstance(*realArgs.toTypedArray()) }
                })
                else -> error("Unknown executable: $methodOrConstructor")
            }
        } catch (e: InvocationTargetException) {
            Result.failure<Any?>(e.targetException)
        }
    }

    /**
     * Does not change bytecode.
     */
    override fun transform(
        loader: ClassLoader?,
        className: String,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain,
        classfileBuffer: ByteArray
    ) = null
}