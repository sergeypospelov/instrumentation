package com.huawei.utbot.instrumentation.instrumentation.instrumenter

import com.huawei.utbot.framework.plugin.api.util.UtContext
import com.huawei.utbot.instrumentation.Settings
import com.huawei.utbot.instrumentation.instrumentation.instrumenter.visitors.MethodToProbesVisitor
import com.huawei.utbot.instrumentation.instrumentation.instrumenter.visitors.util.AddFieldAdapter
import com.huawei.utbot.instrumentation.instrumentation.instrumenter.visitors.util.AddStaticFieldAdapter
import com.huawei.utbot.instrumentation.instrumentation.instrumenter.visitors.util.IInstructionVisitor
import com.huawei.utbot.instrumentation.instrumentation.instrumenter.visitors.util.InstanceFieldInitializer
import com.huawei.utbot.instrumentation.instrumentation.instrumenter.visitors.util.InstructionVisitorAdapter
import com.huawei.utbot.instrumentation.instrumentation.instrumenter.visitors.util.StaticFieldInitializer
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.javaMethod
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode


// TODO: handle with flags EXPAND_FRAMES, etc.

// TODO: compute maxs correctly

/**
 * Helper class for bytecode manipulation operations.
 */

class Instrumenter(classByteCode: ByteArray) {

    var classByteCode: ByteArray = classByteCode.clone()
        private set

    constructor(clazz: Class<*>) : this(computeClassBytecode(clazz))

    fun <T : ClassVisitor> visitClass(classVisitorBuilder: ClassVisitorBuilder<T>): T {
        val reader = ClassReader(classByteCode)
        val writer = ClassWriter(reader, classVisitorBuilder.writerFlags) // TODO: optimize
        val classVisitor = classVisitorBuilder.build(writer)
        reader.accept(classVisitor, classVisitorBuilder.readerParsingOptions)
        classByteCode = writer.toByteArray()
        return classVisitor
    }

    fun computeMapOfRanges(methodName: String? = null): Map<String, IntRange> {
        val methodToListOfProbesInserter = MethodToProbesVisitor()

        visitClass(object : ClassVisitorBuilder<InstructionVisitorAdapter> {
            override val writerFlags: Int
                get() = 0

            override fun build(writer: ClassWriter): InstructionVisitorAdapter =
                InstructionVisitorAdapter(writer, methodName, methodToListOfProbesInserter)
        })

        return methodToListOfProbesInserter.methodToProbes.mapValues { (_, probes) -> (probes.first()..probes.last()) }
    }

    fun addField(instanceFieldInitializer: InstanceFieldInitializer) {
        visitClass { writer -> AddFieldAdapter(writer, instanceFieldInitializer) }
    }

    fun addStaticField(staticFieldInitializer: StaticFieldInitializer) {
        visitClass { writer -> AddStaticFieldAdapter(writer, staticFieldInitializer) }
    }

    fun visitInstructions(instructionVisitor: IInstructionVisitor, methodName: String? = null) {
        visitClass { writer -> InstructionVisitorAdapter(writer, methodName, instructionVisitor) }
    }

    companion object {
        private fun computeClassBytecode(clazz: Class<*>): ByteArray {
            val reader =
                ClassReader(clazz.classLoader.getResourceAsStream(Type.getInternalName(clazz) + ".class"))
            val writer = ClassWriter(reader, 0)
            reader.accept(writer, 0)
            return writer.toByteArray()
        }

        private fun findByteClass(className: String): ClassReader? {
            val path = className.replace(".", File.separator) + ".class"
            return try {
                val classReader = UtContext.currentContext()?.classLoader?.getResourceAsStream(path)
                    ?.readBytes()
                    ?.let { ClassReader(it) }
                    ?: ClassReader(className)
                classReader
            } catch (e: IOException) {
                //TODO: SAT-1222
                null
            }
        }

        // TODO: move the following methods to another file
        private fun computeSourceFileName(className: String): String? {
            val classReader = findByteClass(className)
            val sourceFileAdapter = ClassNode(Settings.ASM_API)
            classReader?.accept(sourceFileAdapter, 0)
            return sourceFileAdapter.sourceFile
        }

        fun computeSourceFileName(clazz: Class<*>): String? {
            return computeSourceFileName(clazz.name)
        }

        fun computeSourceFileByMethod(method: KFunction<*>, directoryToSearchRecursively: Path = Paths.get("")): File? =
            method.javaMethod?.declaringClass?.let {
                computeSourceFileByClass(it, directoryToSearchRecursively)
            }

        fun computeSourceFileByClass(
            className: String,
            packageName: String?,
            directoryToSearchRecursively: Path = Paths.get("")
        ): File? {
            val sourceFileName = computeSourceFileName(className) ?: return null
            val files =
                Files.walk(directoryToSearchRecursively).filter { it.toFile().isFile && it.endsWith(sourceFileName) }
            var fileWithoutPackage: File? = null
            val pathWithPackage = packageName?.let { Paths.get(it, sourceFileName) }
            for (f in files) {
                if (pathWithPackage == null || f.endsWith(pathWithPackage)) {
                    return f.toFile()
                }
                fileWithoutPackage = f.toFile()
            }
            return fileWithoutPackage
        }

        fun computeSourceFileByClass(clazz: Class<*>, directoryToSearchRecursively: Path = Paths.get("")): File? {
            val packageName = clazz.`package`?.name?.replace('.', File.separatorChar)
            return computeSourceFileByClass(clazz.name, packageName, directoryToSearchRecursively)
        }
    }
}

fun interface ClassVisitorBuilder<T : ClassVisitor> {
    val writerFlags: Int
        get() = ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES

    val readerParsingOptions: Int
        get() = ClassReader.SKIP_FRAMES

    fun build(writer: ClassWriter): T
}
