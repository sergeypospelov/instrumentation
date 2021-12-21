package com.huawei.utbot.instrumentation.util

import com.huawei.utbot.framework.plugin.api.util.executableId
import com.huawei.utbot.framework.plugin.api.util.signature
import com.huawei.utbot.instrumentation.Executor
import java.lang.reflect.Method
import kotlin.reflect.KCallable
import kotlin.reflect.jvm.kotlinFunction

/**
 * Class, which creates isolated function from [executableId]. Delegates the function call to the [executor].
 */
class Isolated<TIResult>(
    private val kCallable: KCallable<*>,
    private val executor: Executor<TIResult>
) {
    constructor(
        method: Method,
        executor: Executor<TIResult>
    ) : this(method.kotlinFunction!!, executor)

    operator fun invoke(vararg args: Any?, parameters: Any? = null): TIResult {
        return executor.execute(kCallable, *args, parameters = parameters)
    }

    val name: String by lazy { kCallable.name }
    val signature: String by lazy { kCallable.signature }
}