package me.ddayo.aris

import party.iroiro.luajava.Lua

class LuaMultiReturn(private vararg val vars: Any) {
    companion object {
        fun <T> push(lua: Lua, it: T) {
            when(it) {
                null -> lua.pushNil()
                is Number -> lua.push(it)
                is Boolean -> lua.push(it)
                is String -> lua.push(it)
                is Map<*, *> -> lua.push(it)
                is Class<*> -> lua.pushJavaClass(it)
                is ILuaStaticDecl -> it.toLua(lua)
                else -> lua.pushJavaObject(it as Any)
            }
        }
    }

    fun luaFn(lua: Lua): Int {
        for(x in vars) push(lua, x)
        return vars.size
    }
}