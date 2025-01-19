package me.ddayo.aris


class LuaMultiReturn(private vararg val vars: Any) {
    companion object {
        fun <T> push(engine: LuaEngine, it: T) {
            val lua = engine.currentTask!!.coroutine
            when (it) {
                null -> lua.pushNil()
                is Number -> lua.push(it)
                is Boolean -> lua.push(it)
                is String -> lua.push(it)
                is Map<*, *> -> lua.push(it)
                is Class<*> -> lua.pushJavaClass(it)
                is ILuaStaticDecl -> {
                    lua.pushJavaObject(it)
                    it.toLua(engine)
                }

                else -> lua.pushJavaObject(it as Any)
            }
        }
    }

    fun luaFn(engine: LuaEngine): Int {
        for (x in vars)
            push(engine, x)
        return vars.size
    }

    val size get() = vars.size
}