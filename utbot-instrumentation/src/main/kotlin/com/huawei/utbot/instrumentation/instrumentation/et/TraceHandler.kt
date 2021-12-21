package com.huawei.utbot.instrumentation.instrumentation.et

import com.huawei.utbot.framework.plugin.api.ClassId
import com.huawei.utbot.framework.plugin.api.FieldId
import com.huawei.utbot.instrumentation.Settings
import kotlin.reflect.jvm.javaField
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.LocalVariablesSorter

sealed class InstructionData {
    abstract val line: Int
    abstract val methodSignature: String
}

data class CommonInstruction(
    override val line: Int,
    override val methodSignature: String
) : InstructionData()

data class InvokeInstruction(
    override val line: Int,
    override val methodSignature: String
) : InstructionData()

data class ReturnInstruction(
    override val line: Int,
    override val methodSignature: String
) : InstructionData()

data class ImplicitThrowInstruction(
    override val line: Int,
    override val methodSignature: String
) : InstructionData()

data class ExplicitThrowInstruction(
    override val line: Int,
    override val methodSignature: String
) : InstructionData()

data class PutStaticInstruction(
    override val line: Int,
    override val methodSignature: String,
    val owner: String,
    val name: String,
    val descriptor: String
) : InstructionData()

internal data class ClassToMethod(
    val className: String,
    val methodName: String
)

class ProcessingStorage {
    private val classToId = mutableMapOf<String, Int>()
    private val idToClass = mutableMapOf<Int, String>()

    private val classMethodToId = mutableMapOf<ClassToMethod, Int>()
    private val idToClassMethod = mutableMapOf<Int, ClassToMethod>()

    private val instructionsData = mutableMapOf<Long, InstructionData>()

    fun addClass(className: String): Int {
        val id = classToId.getOrPut(className) { classToId.size }
        idToClass.putIfAbsent(id, className)
        return id
    }

    fun computeId(className: String, localId: Int): Long {
        return classToId[className]!!.toLong() * SHIFT + localId
    }

    fun addClassMethod(className: String, methodName: String): Int {
        val classToMethod = ClassToMethod(className, methodName)
        val id = classMethodToId.getOrPut(classToMethod) { classMethodToId.size }
        idToClassMethod.putIfAbsent(id, classToMethod)
        return id
    }

    fun computeClassNameAndLocalId(id: Long): Pair<String, Int> {
        val className = idToClass.getValue((id / SHIFT).toInt())
        val localId = (id % SHIFT).toInt()
        return className to localId
    }

    fun addInstruction(id: Long, instructionData: InstructionData) {
        instructionsData.putIfAbsent(id, instructionData)
    }

    fun getInstruction(id: Long): InstructionData {
        return instructionsData.getValue(id)
    }

    companion object {
        private const val SHIFT = 1.toLong().shl(32) // 2 ^ 32
    }
}


/**
 * Storage to which instrumented classes will write execution data.
 */
object RuntimeTraceStorage {
    /**
     * Contains ids of instructions in the order of execution.
     */
    @JvmField
    val `$__trace__`: LongArray = LongArray(Settings.TRACE_ARRAY_SIZE)
    const val DESC_TRACE = "[J"

    /**
     * Contains call ids in the order of execution. Call id is a unique number for each function execution.
     */
    @JvmField
    var `$__trace_call_id__`: IntArray = IntArray(Settings.TRACE_ARRAY_SIZE)
    const val DESC_TRACE_CALL_ID = "[I"

    /**
     * Contains current instruction number.
     */
    @JvmField
    var `$__counter__`: Int = 0
    const val DESC_COUNTER = "I"

    /**
     * Contains current call id.
     */
    @JvmField
    var `$__counter_call_id__`: Int = 0
    const val DESC_CALL_ID_COUNTER = "I"
}

class TraceInstructionBytecodeInserter {
    private var localVariable = -1

    private val internalName = Type.getInternalName(RuntimeTraceStorage::class.java)

    private val traceArrayName = RuntimeTraceStorage::`$__trace__`.javaField!!.name
    private val traceCallIdArrayName = RuntimeTraceStorage::`$__trace_call_id__`.javaField!!.name

    private val counterName = RuntimeTraceStorage::`$__counter__`.javaField!!.name
    private val counterCallIdName = RuntimeTraceStorage::`$__counter_call_id__`.javaField!!.name

    fun visitMethodBeginning(mv: MethodVisitor, lvs: LocalVariablesSorter) {
        localVariable = lvs.newLocal(Type.INT_TYPE)

        mv.visitFieldInsn(Opcodes.GETSTATIC, internalName, counterCallIdName, RuntimeTraceStorage.DESC_CALL_ID_COUNTER)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IADD)
        mv.visitInsn(Opcodes.DUP)

        mv.visitFieldInsn(Opcodes.PUTSTATIC, internalName, counterCallIdName, RuntimeTraceStorage.DESC_CALL_ID_COUNTER)
        mv.visitVarInsn(Opcodes.ISTORE, localVariable)
    }

    fun insertUtilityInstructions(mv: MethodVisitor, id: Long): MethodVisitor {
        mv.visitFieldInsn(Opcodes.GETSTATIC, internalName, traceCallIdArrayName, RuntimeTraceStorage.DESC_TRACE_CALL_ID)

        mv.visitFieldInsn(Opcodes.GETSTATIC, internalName, traceArrayName, RuntimeTraceStorage.DESC_TRACE)
        mv.visitFieldInsn(Opcodes.GETSTATIC, internalName, counterName, RuntimeTraceStorage.DESC_COUNTER)
        mv.visitInsn(Opcodes.DUP_X1)
        mv.visitLdcInsn(id)
        mv.visitInsn(Opcodes.LASTORE)

        mv.visitVarInsn(Opcodes.ILOAD, localVariable)

        mv.visitInsn(Opcodes.IASTORE)

        mv.visitFieldInsn(Opcodes.GETSTATIC, internalName, counterName, RuntimeTraceStorage.DESC_COUNTER)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IADD)
        mv.visitFieldInsn(Opcodes.PUTSTATIC, internalName, counterName, RuntimeTraceStorage.DESC_COUNTER)

        return mv
    }
}

class TraceHandler {
    private val processingStorage = ProcessingStorage()
    private val inserter = TraceInstructionBytecodeInserter()

    private var instructionsList: List<Instruction>? = null

    fun registerClass(className: String) {
        processingStorage.addClass(className)
    }

    fun computeInstructionVisitor(className: String): TraceListStrategy {
        return TraceListStrategy(className, processingStorage, inserter)
    }

    fun computeInstructionList(): List<Instruction> {
        if (instructionsList == null) {
            instructionsList = (0 until RuntimeTraceStorage.`$__counter__`).map { ptr ->
                val instrId = RuntimeTraceStorage.`$__trace__`[ptr]
                val curInstrData = processingStorage.getInstruction(instrId)
                val (className, localId) = processingStorage.computeClassNameAndLocalId(instrId)
                val callId = RuntimeTraceStorage.`$__trace_call_id__`[ptr]
                Instruction(className, curInstrData.methodSignature, callId, localId, curInstrData.line, curInstrData)
            }
        }
        return instructionsList!!
    }

    fun computePutStatics(): List<FieldId> =
        computeInstructionList().map { it.instructionData }
            .filterIsInstance<PutStaticInstruction>()
            .map { FieldId(ClassId(it.owner.replace("/", ".")), it.name) }

    fun computeTrace(): Trace {
        val instructionList = computeInstructionList()

        val stack = mutableListOf<TraceNode>()
        val setOfCallIds = mutableSetOf<Int>()
        var root: TraceNode? = null

        for (instr in instructionList) {
            val (className, methodSignature, callId) = instr

            if (stack.isEmpty()) {
                val traceNode = TraceNode(className, methodSignature, callId, depth = 1, mutableListOf())
                traceNode.instructions += instr
                stack += traceNode
                setOfCallIds += callId
                root = traceNode
            } else {
                if (callId in setOfCallIds) {
                    val lastInstrs = stack.last().instructions
                    if (stack.last().callId != callId &&
                        (lastInstrs.lastOrNull() as? Instruction)?.instructionData !is ReturnInstruction
                    ) {
                        val instruction = lastInstrs.last() as Instruction
                        if (instruction.instructionData !is ExplicitThrowInstruction) {
                            lastInstrs[lastInstrs.lastIndex] = instruction.copy(
                                instructionData = ImplicitThrowInstruction(
                                    instruction.line,
                                    instruction.methodSignature
                                )
                            )
                        }
                    }
                    while (stack.last().callId != callId) {
                        setOfCallIds.remove(stack.last().callId)
                        stack.removeLast()
                    }
                    stack.last().instructions += instr
                } else {
                    val traceNode = TraceNode(
                        className,
                        methodSignature,
                        callId,
                        stack.last().depth + 1,
                        mutableListOf()
                    )
                    traceNode.instructions += instr
                    stack.last().instructions += traceNode
                    stack += traceNode
                    setOfCallIds += callId
                }
            }
        }

        val lastInstrs = stack.last().instructions
        val lastInstrType = (lastInstrs.lastOrNull() as? Instruction)?.instructionData
        if (lastInstrType !is ReturnInstruction && lastInstrType !is ExplicitThrowInstruction) {
            lastInstrs[lastInstrs.lastIndex] =
                (lastInstrs.last() as Instruction).run {
                    copy(
                        instructionData = ImplicitThrowInstruction(
                            instructionData.line,
                            instructionData.methodSignature
                        )
                    )
                }
        }

        return Trace(root!!, computePutStatics())
    }

    fun resetTrace() {
        instructionsList = null
        RuntimeTraceStorage.`$__counter__` = 0
    }
}