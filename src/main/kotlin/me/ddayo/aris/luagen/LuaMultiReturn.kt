package me.ddayo.aris.luagen

import party.iroiro.luajava.Lua


class LuaMultiReturn(private vararg val vars: Any) {
    companion object {
        fun <T> push(engine: LuaEngine, lua: Lua, it: T) {
            when (it) {
                null -> lua.pushNil()
                is Number -> lua.push(it)
                is Boolean -> lua.push(it)
                is String -> lua.push(it)
                is Map<*, *> -> lua.push(it)
                is Class<*> -> lua.pushJavaClass(it)
                is ILuaStaticDecl -> {
                    lua.pushJavaObject(it)
                    it.toLua(engine, lua)
                }

                else -> lua.pushJavaObject(it as Any)
            }
        }
    }

    fun luaFn(engine: LuaEngine, lua: Lua): Int {
        for (x in vars)
            push(engine, lua, x)
        return vars.size
    }

    val size get() = vars.size
}