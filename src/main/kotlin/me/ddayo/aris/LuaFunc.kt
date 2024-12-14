package me.ddayo.aris

import party.iroiro.luajava.Lua

class LuaFunc(private val lua: Lua, loc: Int = -1) {
    init {
        if (!lua.isFunction(loc))
            throw Exception("Lua function expected but got ${lua.type(loc)}")
        lua.pushValue(loc)
    }

    val ref = lua.ref()

    fun call(vararg values: Any) {
        lua.refGet(ref)

        var a = 0
        for (x in values) a += LuaMain.pushNoInline(lua, x)

        lua.pCall(a, 0)
    }

    fun finalize() {
        // commented because this may execute on other thread
        // lua.unref(ref)
    }
}