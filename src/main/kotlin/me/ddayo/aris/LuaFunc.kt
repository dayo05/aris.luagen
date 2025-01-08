package me.ddayo.aris

import java.lang.ref.Cleaner

class LuaFunc(private val task: LuaEngine.LuaTask, loc: Int = -1) {
    companion object {
        private val luaFuncCleaner = Cleaner.create()
    }
    private val lua = task.coroutine
    init {
        task.externalRefCount++
        if (!lua.isFunction(loc))
            throw Exception("Lua function expected but got ${lua.type(loc)}")
        lua.pushValue(loc)

        luaFuncCleaner.register(this) {
            lua.unref(ref)
            task.externalRefCount--
        }
    }

    private val ref = lua.ref()

    fun call(vararg values: Any) {
        lua.refGet(ref)

        var a = 0
        for (x in values) a += LuaMain.pushNoInline(lua, x)

        lua.pCall(a, 0)
    }
}