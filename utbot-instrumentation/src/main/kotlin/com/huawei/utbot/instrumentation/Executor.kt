package com.huawei.utbot.instrumentation

import com.huawei.utbot.framework.plugin.api.ExecutableId
import kotlin.reflect.KCallable

/**
 * Base interface for delegated execution logic.
 *
 * @param TResult the type of an execution result.
 */
interface Executor<TResult> {
    /**
     * Main method to override.
     * Returns the result of the execution of the [ExecutableId] with [arguments] and [parameters].
     *
     * @param arguments are additional data, e.g. static environment.
     */
    fun execute(
        kCallable: KCallable<*>,
        vararg arguments: Any?,
        parameters: Any? = null
    ): TResult
}

