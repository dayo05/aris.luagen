package me.ddayo.aris

import party.iroiro.luajava.Lua


class LuaFunc(private val engine: LuaEngine, private val lua: Lua, loc: Int = -1) {
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
}