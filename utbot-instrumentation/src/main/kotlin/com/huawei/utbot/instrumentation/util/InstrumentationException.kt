package com.huawei.utbot.instrumentation.util

// TODO: refactor this

/**
 * Base class for instrumentation exceptions.
 */
open class InstrumentationException(msg: String?, cause:Throwable? = null) : Exception(msg, cause)

class NoProbesArrayException(arrayName: String) :
    InstrumentationException("No probes array found. Probes array name: $arrayName")

class CastProbesArrayException :
    InstrumentationException("Can't cast probes array to Boolean array")

class LoadingFromKryoException(clazz: Class<*>) :
    InstrumentationException("Can't load ${clazz.canonicalName}")

class ChildProcessError(val innerException: Throwable) :
    InstrumentationException("Error in the child process: $innerException")

class UnexpectedCommand(cmd: Protocol.Command) :
    InstrumentationException("Got unexpected command: $cmd")

class ChildProcessStartException(override val cause: Throwable) :
    InstrumentationException("Error during the child process start. Cause:\n${cause.localizedMessage}", cause = cause)