package com.huawei.utbot.instrumentation.instrumentation.mock

import com.huawei.utbot.instrumentation.Settings
import com.huawei.utbot.instrumentation.instrumentation.instrumenter.visitors.util.FieldInitializer
import java.lang.reflect.Method
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.AdviceAdapter

object MockConfig {
    const val IS_MOCK_FIELD = "\$__is_mock_"
}

class MockClassVisitor(
    classVisitor: ClassVisitor,
    mockGetter: Method
) : ClassVisitor(Settings.ASM_API, classVisitor) {
    val signatureToId = mutableMapOf<String, Int>()

    private lateinit var internalClassName: String
    private val extraFields = mutableListOf<FieldInitializer>()

    private val mockGetterOwner = Type.getType(mockGetter.declaringClass)
    private val mockGetterMethod = org.objectweb.asm.commons.Method.getMethod(mockGetter)

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        internalClassName = name
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        return if (name != "<clinit>" && name != "<init>") { // we do not want to mock <init> or <clinit>
            visitStaticMethod(access, name, descriptor, signature, exceptions)
        } else {
            cv.visitMethod(access, name, descriptor, signature, exceptions)
        }
    }

    private fun visitStaticMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val isStatic = access and Opcodes.ACC_STATIC != 0
        val isVoidMethod = Type.getReturnType(descriptor) == Type.VOID_TYPE

        val computedSignature = name + descriptor
        val id = signatureToId.size
        signatureToId[computedSignature] = id

        val isMockInitializer =
            StaticPrimitiveInitializer(internalClassName, MockConfig.IS_MOCK_FIELD + id, Type.BOOLEAN_TYPE)
        extraFields += isMockInitializer

        val mv = cv.visitMethod(access, name, descriptor, signature, exceptions)
        return object : AdviceAdapter(Settings.ASM_API, mv, access, name, descriptor) {
            override fun onMethodEnter() {
                val afterIfLabel = Label()

                visitFieldInsn(
                    GETSTATIC,
                    internalClassName,
                    isMockInitializer.name,
                    isMockInitializer.descriptor
                )
                ifZCmp(IFEQ, afterIfLabel)

                if (isVoidMethod) {
                    visitInsn(RETURN)
                } else {
                    if (isStatic) {
                        visitInsn(ACONST_NULL)
                    } else {
                        loadThis()
                    }
                    push(computedSignature)

                    invokeStatic(mockGetterOwner, mockGetterMethod)

                    if (returnType.sort == Type.OBJECT || returnType.sort == Type.ARRAY) {
                        checkCast(returnType)
                    } else { // primitive here
                        unbox(returnType)
                    }
                    returnValue()
                }

                visitLabel(afterIfLabel)
            }
        }
    }

    override fun visitEnd() {
        extraFields.forEach { addField(it) }
        cv.visitEnd()
    }

    private fun addField(field: FieldInitializer) {
        val fv = cv.visitField(
            Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC + Opcodes.ACC_SYNTHETIC + Opcodes.ACC_FINAL,
            field.name,
            field.descriptor,
            field.signature,
            null
        )
        fv.visitEnd()
    }
}

