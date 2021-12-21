package com.huawei.utbot.instrumentation.process

import com.huawei.utbot.framework.plugin.api.util.UtContext
import com.huawei.utbot.instrumentation.agent.Agent
import com.huawei.utbot.instrumentation.instrumentation.Instrumentation
import com.huawei.utbot.instrumentation.util.KryoHelper
import com.huawei.utbot.instrumentation.util.Protocol
import com.huawei.utbot.instrumentation.util.UnexpectedCommand
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URLClassLoader

/**
 * We use this ClassLoader to separate user's classes and our dependency classes.
 * Our classes won't be instrumented.
 */
internal object HandlerClassesLoader : URLClassLoader(emptyArray()) {
    fun addUrls(urls: Iterable<String>) {
        urls.forEach { super.addURL(File(it).toURI().toURL()) }
    }
}

/**
 * It should be compiled into separate jar file (child_process.jar) and be run with an agent (agent.jar) option.
 */
fun main() {
    val stdInOutProcessConnector =
        object : IProcessConnector {
            override val processOutputStream: InputStream = System.`in`
            override val processInputStream: OutputStream = System.`out`

            override fun close() {
                processOutputStream.close()
                processInputStream.close()
            }
        }
    val kryoHelper =
        KryoHelper(
            "Child process",
            stdInOutProcessConnector.processOutputStream,
            stdInOutProcessConnector.processInputStream
        )
    kryoHelper.writeCommand(Protocol.ProcessReadyCommand())

    val classPaths = readClasspath(kryoHelper)
    val pathsToUserClasses = classPaths.pathsToUserClasses.split(File.pathSeparatorChar)
    val pathsToDependencyClasses = classPaths.pathsToDependencyClasses.split(File.pathSeparatorChar)
    HandlerClassesLoader.addUrls(pathsToUserClasses)
    HandlerClassesLoader.addUrls(pathsToDependencyClasses)
    kryoHelper.setKryoClassLoader(HandlerClassesLoader) // Now kryo will use our classloader when it encounters unregistered class.

    System.err.println(pathsToUserClasses.joinToString())

    UtContext.setUtContext(UtContext(HandlerClassesLoader)).use {
        getInstrumentation(kryoHelper)?.let {
            Agent.dynamicClassTransformer.transformer = it // classTransformer is set
            Agent.dynamicClassTransformer.addPaths(pathsToUserClasses)
            loop(kryoHelper, it, stdInOutProcessConnector)
        }
    }
}

/**
 * Main loop. Processes incoming commands.
 */
private fun loop(kryoHelper: KryoHelper, instrumentation: Instrumentation<*>, processConnector: IProcessConnector) {
    while (true) {
        when (val cmd = kryoHelper.readCommand()) {
            is Protocol.InvokeMethodCommand -> {
                val resultCmd = try {
                    val clazz = HandlerClassesLoader.loadClass(cmd.className)
                    val res = instrumentation.invoke(
                        clazz,
                        cmd.signature,
                        cmd.arguments,
                        cmd.parameters
                    )
                    Protocol.InvocationResultCommand(res)
                } catch (e: Exception) {
                    Protocol.ErrorCommand(e)
                }
                kryoHelper.writeCommand(resultCmd)
            }
            is Protocol.StopProcessCommand -> {
                processConnector.close()
                break
            }
            is Protocol.InstrumentationCommand -> {
                val result = instrumentation.handle(cmd)
                result?.let {
                    kryoHelper.writeCommand(it)
                }
            }
            else -> {
                kryoHelper.writeCommand(Protocol.ErrorCommand(UnexpectedCommand(cmd)))
            }
        }
    }
}

/**
 * Retrieves the actual instrumentation. It is passed from the main process during
 * [com.huawei.utbot.instrumentation.ConcreteExecutor] instantiation.
 */
private fun getInstrumentation(kryoHelper: KryoHelper): Instrumentation<*>? {
    return when (val cmd = kryoHelper.readCommand()) {
        is Protocol.SetInstrumentationCommand<*> -> {
            kryoHelper.writeCommand(Protocol.ProcessReadyCommand())
            cmd.instrumentation
        }
        is Protocol.StopProcessCommand -> null
        else -> {
            kryoHelper.writeCommand(Protocol.ErrorCommand(UnexpectedCommand(cmd)))
            null
        }
    }
}

fun readClasspath(kryoHelper: KryoHelper): Protocol.AddPathsCommand {
    return kryoHelper.readCommand().let { cmd ->
        if (cmd is Protocol.AddPathsCommand) {
            kryoHelper.writeCommand(Protocol.ProcessReadyCommand())
            cmd
        } else {
            kryoHelper.writeCommand(Protocol.ErrorCommand(UnexpectedCommand(cmd)))
            error("No classpath!")
        }
    }
}