package com.huawei.utbot.instrumentation

import com.huawei.utbot.framework.plugin.api.util.signature
import com.huawei.utbot.instrumentation.instrumentation.Instrumentation
import com.huawei.utbot.instrumentation.process.ChildProcessRunner
import com.huawei.utbot.instrumentation.util.ChildProcessError
import com.huawei.utbot.instrumentation.util.ChildProcessStartException
import com.huawei.utbot.instrumentation.util.KryoHelper
import com.huawei.utbot.instrumentation.util.Protocol
import com.huawei.utbot.instrumentation.util.UnexpectedCommand
import java.io.Closeable
import kotlin.reflect.KCallable
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
import kotlin.reflect.jvm.javaConstructor
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.javaMethod
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Creates [ConcreteExecutor], which delegates `execute` calls to the child process, and applies the given [block] to it.
 *
 * The child process will search for the classes in [pathsToUserClasses] and will use [instrumentation] for instrumenting.
 *
 * Specific instrumentation can add functionality to [ConcreteExecutor] via Kotlin extension functions.
 *
 * @param TIResult the return type of [Instrumentation.invoke] function for the given [instrumentation].
 * @return the result of the block execution on created [ConcreteExecutor].
 *
 * @see [com.huawei.utbot.instrumentation.instrumentation.coverage.CoverageInstrumentation].
 */
inline fun <TBlockResult, TIResult, reified T : Instrumentation<TIResult>> withInstrumentation(
    instrumentation: T,
    pathsToUserClasses: String,
    pathsToDependencyClasses: String = ConcreteExecutor.defaultPathsToDependencyClasses,
    block: (ConcreteExecutor<TIResult, T>) -> TBlockResult
) = ConcreteExecutor(instrumentation, pathsToUserClasses, pathsToDependencyClasses).use {
    block(it)
}

class ConcreteExecutorPool(val maxCount: Int = 10) : AutoCloseable {
    private val executors = ArrayDeque<ConcreteExecutor<*, *>>(maxCount)

    /**
     * Tries to find the concrete executor for the supplied [instrumentation] and [pathsToDependencyClasses]. If it
     * doesn't exist, then creates a new one.
     */
    fun <TIResult, TInstrumentation : Instrumentation<TIResult>> get(
        instrumentation: TInstrumentation,
        pathsToUserClasses: String,
        pathsToDependencyClasses: String
    ): ConcreteExecutor<TIResult, TInstrumentation> {
        @Suppress("UNCHECKED_CAST")
        return executors.firstOrNull {
            it.pathsToUserClasses == pathsToUserClasses && it.instrumentation == instrumentation
        } as? ConcreteExecutor<TIResult, TInstrumentation>
            ?: ConcreteExecutor.createNew(instrumentation, pathsToUserClasses, pathsToDependencyClasses).apply {
                executors.addFirst(this)
                if (executors.size > maxCount) {
                    executors.removeLast().close()
                }
            }
    }

    override fun close() {
        executors.forEach { it.close() }
        executors.clear()
    }
}

/**
 * Concrete executor class. Takes [pathsToUserClasses] where the child process will search for the classes. Paths should
 * be separated with [java.io.File.pathSeparatorChar].
 *
 * If [instrumentation] depends on other classes, they should be passed in [pathsToDependencyClasses].
 *
 * Also takes [instrumentation] object which will be used in the child process for the instrumentation.
 *
 * @param TIResult the return type of [Instrumentation.invoke] function for the given [instrumentation].
 */
class ConcreteExecutor<TIResult, TInstrumentation : Instrumentation<TIResult>> private constructor(
    internal val instrumentation: TInstrumentation,
    internal val pathsToUserClasses: String,
    internal val pathsToDependencyClasses: String
) : Closeable, Executor<TIResult> {

    companion object {
        val defaultPool = ConcreteExecutorPool()
        var defaultPathsToDependencyClasses = ""

        /**
         * Delegates creation of the concrete executor to [defaultPool], which first searches for existing executor
         * and in case of failure, creates a new one.
         */
        operator fun <TIResult, TInstrumentation : Instrumentation<TIResult>> invoke(
            instrumentation: TInstrumentation,
            pathsToUserClasses: String,
            pathsToDependencyClasses: String = defaultPathsToDependencyClasses
        ) = defaultPool.get(instrumentation, pathsToUserClasses, pathsToDependencyClasses)

        internal fun <TIResult, TInstrumentation : Instrumentation<TIResult>> createNew(
            instrumentation: TInstrumentation,
            pathsToUserClasses: String,
            pathsToDependencyClasses: String
        ) = ConcreteExecutor(instrumentation, pathsToUserClasses, pathsToDependencyClasses)
    }

    private val childProcessRunner = ChildProcessRunner()
    private val kryoHelper = KryoHelper("ConcreteExecutor")

    init {
        restartIfNeeded()
    }

    /**
     * Executes [kCallable] in the child process with the supplied [arguments] and [parameters], e.g. static environment.
     *
     * @return the processed result of the method call.
     */
    override fun execute(
        kCallable: KCallable<*>,
        vararg arguments: Any?,
        parameters: Any?
    ): TIResult {
        restartIfNeeded()
        val (clazz, signature) = when (kCallable) {
            is KFunction<*> -> kCallable.javaMethod?.run { declaringClass to signature }
                ?: kCallable.javaConstructor?.run { declaringClass to signature }
                ?: error("Not a constructor or a method")
            is KProperty<*> -> kCallable.javaGetter?.run { declaringClass to signature }
                ?: error("Not a getter")
            else -> error("Unknown KCallable: $kCallable")
        } // actually executableId implements the same logic, but it requires UtContext

        val invokeMethodCommand = Protocol.InvokeMethodCommand(
            clazz.name,
            signature,
            arguments.asList(),
            parameters
        )

        kryoHelper.writeCommand(invokeMethodCommand)

        return onCommand<Protocol.InvocationResultCommand<*>, TIResult> {
            @Suppress("UNCHECKED_CAST")
            it.result as TIResult
        }
    }

    /**
     * Restarts the child process if it is not active.
     */
    private fun restartIfNeeded() {
        if (isRunning) {
            return
        }

        try {
            logger.debug { "Restarting the child process with instrumentation=${instrumentation.javaClass.simpleName} and classpath:'$pathsToUserClasses'" }
            childProcessRunner.start()

            val processConnector = childProcessRunner.processConnector

            kryoHelper.setInputStream(processConnector.processOutputStream)
            kryoHelper.setOutputStream(processConnector.processInputStream)

            waitForReady()

            passClassPathToChildProcess()

            passInstrumentationToChildProcess(instrumentation)
        } catch (e: Throwable) {
            throw ChildProcessStartException(e)
        }
    }

    private fun passClassPathToChildProcess() {
        kryoHelper.writeCommand(Protocol.AddPathsCommand(pathsToUserClasses, pathsToDependencyClasses))
        waitForReady()
    }

    private fun passInstrumentationToChildProcess(instrumentation: TInstrumentation) {
        kryoHelper.writeCommand(Protocol.SetInstrumentationCommand(instrumentation))
        waitForReady()
    }

    /**
     * Sends [requestCmd] to the ChildProcess.
     * If [action] is not null, waits for the response command, performs [action] on it and returns the result.
     * This function is helpful for creating extensions for specific instrumentations.
     * @see [com.huawei.utbot.instrumentation.instrumentation.coverage.CoverageInstrumentation].
     */
    fun <T : Protocol.Command, R> request(requestCmd: T, action: ((Protocol.Command) -> R)?): R? {
        restartIfNeeded()
        kryoHelper.writeCommand(requestCmd)
        return action?.let { it(kryoHelper.readCommand()) }
    }

    /**
     * Helper function for duplicate code.
     */
    private inline fun <reified T : Protocol.Command, R> onCommand(action: (T) -> R): R =
        when (val cmd = kryoHelper.readCommand()) {
            is T -> action(cmd)
            is Protocol.ErrorCommand -> throw ChildProcessError(cmd.exception)
            else -> throw UnexpectedCommand(cmd)
        }

    /**
     * Helper function for duplicate code.
     */
    private fun waitForReady() {
        onCommand<Protocol.ProcessReadyCommand, Unit> { }
    }

    val isRunning: Boolean
        get() = childProcessRunner.isRunning

    override fun close() {
        if (!isRunning) {
            return
        }
        kryoHelper.writeCommand(Protocol.StopProcessCommand())
        childProcessRunner.waitFor()
    }
}