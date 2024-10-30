package me.ddayo.aris

import me.ddayo.aris.gen.LuaGenerated.CoroutineReturn_LuaGenerated
import me.ddayo.aris.gen.LuaGenerated.LuaCoroutineIntegration_LuaGenerated
import me.ddayo.aris.luagen.LuaFunction
import me.ddayo.aris.luagen.LuaProvider
import kotlin.experimental.ExperimentalTypeInference

interface CoroutineProvider {
    @OptIn(ExperimentalTypeInference::class)
    fun <T> coroutine(@BuilderInference block: suspend SequenceScope<CoroutineReturn<T>>.() -> Unit)
        = LuaCoroutineIntegration(sequence(block))

    suspend fun <T> SequenceScope<CoroutineReturn<T>>.breakTask() {
        yield(CoroutineReturn.breakTask())
    }

    suspend fun <T> SequenceScope<CoroutineReturn<T>>.breakTask(value: T) {
        yield(CoroutineReturn.breakTask(value))
    }

    suspend fun SequenceScope<CoroutineReturn<LuaMultiReturn>>.breakTask(vararg value: Any) {
        yield(CoroutineReturn.breakTask(LuaMultiReturn(*value)))
    }

    suspend fun <T> SequenceScope<CoroutineReturn<T>>.yieldUntil(until: () -> Boolean) {
        yield(CoroutineReturn.yield(until))
    }

    @LuaProvider
    class LuaCoroutineIntegration<T>(sequence: Sequence<CoroutineReturn<T>>): ILuaStaticDecl by LuaCoroutineIntegration_LuaGenerated {
        private val iter = sequence.iterator()

        @LuaFunction("next_iter")
        fun nextIter(): CoroutineReturn<T> {
            if(!iter.hasNext()) return CoroutineReturn.breakTask()
            return iter.next()
        }
    }

    @LuaProvider
    class CoroutineReturn<T>(val _isBreak: Boolean): ILuaStaticDecl by CoroutineReturn_LuaGenerated {
        var returnValue: T? = null
        var until: (() -> Boolean)? = null

        @LuaFunction("finished")
        fun finished() = until?.invoke() == true
        @LuaFunction("value")
        fun value() = returnValue
        @LuaFunction("is_break")
        fun isBreak() = _isBreak

        companion object {
            fun <T> breakTask(value: T) = CoroutineReturn<T>(true).apply { returnValue = value }
            fun <T> breakTask() = CoroutineReturn<T>(true)
            fun <T> yield(until: () -> Boolean) = CoroutineReturn<T>(false).apply { this.until = until }
        }
    }
}