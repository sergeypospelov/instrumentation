package com.huawei.utbot.instrumentation.process

import com.huawei.utbot.common.packageName
import com.huawei.utbot.common.scanForResourcesContaining
import com.huawei.utbot.instrumentation.Settings
import com.huawei.utbot.instrumentation.agent.DynamicClassTransformer
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.IllegalStateException

/**
 * Helper class for interacting with the child process.
 */

class ChildProcessRunner {

    private val jarFile: File by lazy {
        try {
            ChildProcessRunner::class.java.classLoader
                .scanForResourcesContaining(DynamicClassTransformer::class.java.packageName)
                .first { it.nameWithoutExtension.startsWith("utbot-instrumentation") && it.extension == "jar" }
        } catch (e: NoSuchElementException) {
            throw IllegalStateException("Failed to find utbot-instrumentation dependency", e)
        }
    }

    private val cmds: List<String> by lazy {
        val debugCmd = if (Settings.runChildProcessWithDebug) {
            listOf(DEBUG_RUN_CMD)
        } else {
            emptyList()
        }

        listOf("java") + debugCmd + listOf("-javaagent:$jarFile", "-jar", "$jarFile")
    }

    private val processBuilder: ProcessBuilder by lazy {
        val tempFile = File.createTempFile(ERRORS_DIR_NAME + hashCode(), ".log")
        ProcessBuilder(cmds).redirectError(tempFile)
    }

    private var process: Process? = null

    val isRunning: Boolean
        get() = process?.isAlive ?: false

    fun waitFor(): Int? {
        return process?.waitFor()
    }

    fun start() {
        if (!isRunning) {
            process = processBuilder.start()
        }
    }

    val processConnector
        get() = process?.let { ProcessConnector(it) } ?: error("Child process has not started")

    companion object {
        private const val JAR_FILE_NAME = "utbot-instrumentation-1.0-SNAPSHOT.jar"
        private const val ERRORS_DIR_NAME = "utbot-childprocess-errors"

        private const val DEBUG_RUN_CMD = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,quiet=y,address=5005"
    }
}

class ProcessConnector(process: Process) : IProcessConnector {
    override val processOutputStream: InputStream = process.inputStream ?: error("Child process has not started")
    override val processInputStream: OutputStream = process.outputStream ?: error("Child process has not started")
    val processErrorStream: InputStream = process.errorStream ?: error("Child process is not alive")

    override fun close() {
        processOutputStream.close()
        processInputStream.close()
        processErrorStream.close()
    }
}