package me.ddayo.aris.luagen

import party.iroiro.luajava.Lua


class LuaFunc(val engine: LuaEngine, creationLua: Lua, loc: Int = -1) : CoroutineProvider {
    private val task = engine.currentTask!!
    private val ref: Int

    // All later calls go through the engine's main Lua state. `creationLua` is
    // the coroutine of the task that registered this function, and that
    // coroutine is closed once the task finishes. A LuaFunc stored past that
    // point (e.g. an entity-goal callback fired long after the registering
    // script ended) would otherwise call into a dead thread and crash with
    // "no more stack space available". The registry ref is shared across the
    // whole Lua state, so retrieving it from the main state is valid.
    private val lua = engine.lua

    init {
        if (!creationLua.isFunction(loc))
            throw Exception("Lua function expected but got ${creationLua.type(loc)}")
        creationLua.pushValue(loc)
        ref = creationLua.ref()
        task.ref(this, ref)
    }

    fun callRawArg(arg: LuaFunc.() -> Int) {
        lua.refGet(ref)
        // nResults must be 0, not LUA_MULTRET (-1): call() discards the result,
        // so any return values left on the stack would accumulate every call
        // until the Lua stack is exhausted ("no more stack space available").
        lua.pCall(arg(), 0)
    }

    fun call(vararg values: Any?) {
        callRawArg {
            var a = 0
            for (x in values) a += engine.luaMain.pushNoInline(lua, x)
            a
        }
    }

    fun callAsTaskRawArg(arg: LuaFunc.(task: LuaEngine.LuaTask) -> Int): LuaEngine.LuaTask {
        // TODO: maybe there are better implementation...
        val task = engine.LuaTask(task.name + "_function")
        lua.refGet(ref)
        task.refIdx = lua.ref()

        task.init()
        task.initArgc = arg(task)

        engine.tasks.add(task)
        return task
    }

    fun callAsTask(vararg values: Any?) = callAsTaskRawArg { task ->
        var a = 0
        for (x in values)
            a += engine.luaMain.pushNoInline(task.coroutine, x)
        a
    }

    fun <T> await(cr: SequenceScope<CoroutineProvider.CoroutineReturn<T>>, vararg values: Any?) = cr.run {
        val task = callAsTask(*values)
        CoroutineProvider.CoroutineReturn.yield<T> {
            !engine.tasks.contains(task)
        }
    }
}