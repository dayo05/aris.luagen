package me.ddayo.aris

import party.iroiro.luajava.Lua


class LuaFunc(val engine: LuaEngine, private val lua: Lua, loc: Int = -1) : CoroutineProvider {
    private val task = engine.currentTask!!
    private val ref: Int

    init {
        if (!lua.isFunction(loc))
            throw Exception("Lua function expected but got ${lua.type(loc)}")
        lua.pushValue(loc)
        ref = lua.ref()
        task.ref(this, ref)
    }

    fun callRawArg(arg: LuaFunc.() -> Int) {
        lua.refGet(ref)
        lua.pCall(arg(), -1)
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
        task.refIdx = ref
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