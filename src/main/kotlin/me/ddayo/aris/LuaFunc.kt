package me.ddayo.aris


class LuaFunc(engine: LuaEngine, loc: Int = -1) {
    private val task = engine.currentTask!!
    private val lua = task.coroutine
    init {
        if (!lua.isFunction(loc))
            throw Exception("Lua function expected but got ${lua.type(loc)}")
        lua.pushValue(loc)
    }

    private val ref = lua.ref()
    init {
        task.ref(this, ref)
    }

    fun call(vararg values: Any) {
        lua.refGet(ref)

        var a = 0
        for (x in values) a += LuaMain.pushNoInline(lua, x)

        lua.pCall(a, 0)
    }
}