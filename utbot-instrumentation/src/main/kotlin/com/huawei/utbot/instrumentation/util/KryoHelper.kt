package com.huawei.utbot.instrumentation.util

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.SerializerFactory
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy
import de.javakaffee.kryoserializers.GregorianCalendarSerializer
import de.javakaffee.kryoserializers.JdkProxySerializer
import de.javakaffee.kryoserializers.SynchronizedCollectionsSerializer
import de.javakaffee.kryoserializers.UnmodifiableCollectionsSerializer
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationHandler
import java.util.GregorianCalendar
import org.objenesis.instantiator.ObjectInstantiator
import org.objenesis.strategy.StdInstantiatorStrategy

/**
 * Helpful class for working with the kryo.
 *
 * @property process either `child process` or `main process`.
 */
class KryoHelper internal constructor(val process: String, private val kryo: Kryo = TunedKryo()) {
    private lateinit var kryoInput: Input
    private lateinit var kryoOutput: Output

    constructor(process: String, inputStream: InputStream, outputStream: OutputStream) : this(process) {
        setInputStream(inputStream)
        setOutputStream(outputStream)
    }

    fun setInputStream(inputStream: InputStream) {
        kryoInput = Input(inputStream)
    }

    fun setOutputStream(outputStream: OutputStream) {
        kryoOutput = Output(outputStream)
    }

    fun setKryoClassLoader(classLoader: ClassLoader) {
        kryo.classLoader = classLoader
    }

    fun writeObject(obj: Any) {
        kryo.writeClassAndObject(kryoOutput, obj)
        kryoOutput.flush()
    }

    fun <T> readObject(): T {
        @Suppress("UNCHECKED_CAST")
        return kryo.readClassAndObject(kryoInput) as T
    }

    fun <T : Protocol.Command> writeCommand(cmd: T) {
        kryo.writeClassAndObject(kryoOutput, cmd)
        kryoOutput.flush()
    }

    fun readCommand(): Protocol.Command {
        val cmd = kryo.readClassAndObject(kryoInput)
        return cmd as? Protocol.Command
            ?: throw LoadingFromKryoException(Protocol.Command::class.java)
    }

    fun <T> registerClass(objClass: Class<T>) {
        kryo.register(objClass)
    }
}

// This kryo is used to initialize collections properly.
internal class TunedKryo : Kryo() {
    init {
        this.references = true
        this.isRegistrationRequired = false

        this.instantiatorStrategy = object : StdInstantiatorStrategy() {
            // workaround for Collections as they cannot be correctly deserialized without calling constructor
            val default = DefaultInstantiatorStrategy()
            val classesBadlyDeserialized = listOf(
                java.util.Queue::class.java,
                java.util.HashSet::class.java
            )

            override fun <T : Any> newInstantiatorOf(type: Class<T>): ObjectInstantiator<T> {
                return if (classesBadlyDeserialized.any { it.isAssignableFrom(type) }) {
                    @Suppress("UNCHECKED_CAST")
                    default.newInstantiatorOf(type) as ObjectInstantiator<T>
                } else {
                    super.newInstantiatorOf(type)
                }
            }
        }

        this.register(GregorianCalendar::class.java, GregorianCalendarSerializer())
        this.register(InvocationHandler::class.java, JdkProxySerializer())
        UnmodifiableCollectionsSerializer.registerSerializers(this)
        SynchronizedCollectionsSerializer.registerSerializers(this)

        val factory = object : SerializerFactory.FieldSerializerFactory() {}
        factory.config.ignoreSyntheticFields = true
        factory.config.serializeTransient = false
        factory.config.fieldsCanBeNull = true
        this.setDefaultSerializer(factory)
    }
}