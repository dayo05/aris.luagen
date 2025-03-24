package me.ddayo.aris

import party.iroiro.luajava.Lua


class LuaFunc(private val engine: LuaEngine, private val lua: Lua, loc: Int = -1): CoroutineProvider {
    private val task = engine.currentTask!!
    private val ref: Int
    init {
        if (!lua.isFunction(loc))
            throw Exception("Lua function expected but got ${lua.type(loc)}")
        lua.pushValue(loc)
        ref = lua.ref()
        task.ref(this, ref)
    }

    fun call(vararg values: Any) {
        lua.refGet(ref)

        var a = 0
        for (x in values) a += engine.luaMain.pushNoInline(lua, x)

        lua.pCall(a, -1)
    }

    fun callAsTask(vararg values: Any): LuaEngine.LuaTask {
        // TODO: maybe there are better implementation...
        val task = engine.LuaTask(task.name + "_function", values.size)
        task.refIdx = ref
        task.init()

        var a = 0
        for (x in values)
            a += engine.luaMain.pushNoInline(task.coroutine, x)


        engine.tasks.add(task)
        return task
    }

    fun <T> await(cr: SequenceScope<CoroutineProvider.CoroutineReturn<T>>, vararg values: Any) = cr.run {
            val task = callAsTask(*values)
            CoroutineProvider.CoroutineReturn.yield<T> {
                !engine.tasks.contains(task)
            }
        }
}