package com.huawei.utbot.instrumentation.process

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * Helper interface for [com.huawei.utbot.instrumentation.util.KryoHelper].
 *
 * Implementations of this are created in the child process and in the main process.
 */
interface IProcessConnector : Closeable {
    val processOutputStream: InputStream
    val processInputStream: OutputStream
}