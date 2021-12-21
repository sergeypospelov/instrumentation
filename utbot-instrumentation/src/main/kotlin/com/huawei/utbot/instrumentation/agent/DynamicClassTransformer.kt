package com.huawei.utbot.instrumentation.agent

import java.io.File
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain

/**
 * Transformer, which will transform only classes with certain names.
 */
class DynamicClassTransformer : ClassFileTransformer {
    lateinit var transformer: ClassFileTransformer

    private val pathsToUserClasses = mutableSetOf<String>()

    private fun normalize(path: String) = File(path).path

    fun addPaths(paths: Iterable<String>) {
        pathsToUserClasses += paths.map { normalize(it) }
    }

    override fun transform(
        loader: ClassLoader?,
        className: String,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain,
        classfileBuffer: ByteArray
    ): ByteArray? {
        val pathToClassfile = normalize(protectionDomain.codeSource.location.path)
        return if (pathToClassfile in pathsToUserClasses || className.contains("slf4j")) {
            transformer.transform(loader, className, classBeingRedefined, protectionDomain, classfileBuffer)

        } else {
            null
        }
    }
}