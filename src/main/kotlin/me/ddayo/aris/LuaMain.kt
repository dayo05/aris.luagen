package me.ddayo.aris

import party.iroiro.luajava.Lua

class LuaMain(val engine: LuaEngine) {

    fun pushNoInline(lua: Lua, it: Any?): Int {
        when (it) {
            null -> {
                lua.pushNil()
                return 1
            }

            is Number -> {
                lua.push(it)
                return 1
            }

            is Boolean -> {
                lua.push(it)
                return 1
            }

            is String -> {
                lua.push(it)
                return 1
            }

            is Map<*, *> -> {
                lua.push(it)
                return 1
            }

            is Class<*> -> {
                lua.pushJavaClass(it)
                return 1
            }

            is ILuaStaticDecl -> {
                lua.pushJavaObject(it)
                it.toLua(engine, lua)
                return 1
            }

            is LuaMultiReturn -> {
                it.luaFn(engine, lua)
                return it.size
            }

            is Unit -> {
                return 0
            }

            else -> {
                lua.pushJavaObject(it as Any)
                return 1
            }
        }
    }

    inline fun <reified T> push(lua: Lua, it: T): Int {
        when (it) {
            null -> {
                lua.pushNil()
                return 1
            }

            is Number -> {
                lua.push(it)
                return 1
            }

            is Boolean -> {
                lua.push(it)
                return 1
            }

            is String -> {
                lua.push(it)
                return 1
            }

            is Map<*, *> -> {
                lua.push(it)
                return 1
            }

            is Class<*> -> {
                lua.pushJavaClass(it)
                return 1
            }

            is ILuaStaticDecl -> {
                lua.pushJavaObject(it)
                it.toLua(engine, lua)
                return 1
            }

            is LuaMultiReturn -> {
                it.luaFn(engine, lua)
                return it.size
            }

            is Unit -> {
                return 0
            }

            else -> {
                lua.pushJavaObject(it as Any)
                return 1
            }
        }
    }

    var _luaGlobalMt = -1
        private set
    private var _luaGcInternal = -1

    init {
        val lua = engine.lua
        lua.openLibraries()

        run {
            lua.pushJavaObject(Any())
            lua.getMetatable(-1)
            _luaGlobalMt = lua.ref()

            if (lua.getMetaField(-1, "__gc") == 0) throw NoSuchElementException("Cannot retrieve __gc metafield")
            _luaGcInternal = lua.ref()
            lua.push { lua ->
                // lua.pushTable(...)
                lua.refGet(_luaGlobalMt)
                lua.setMetatable(-2)
                lua.refGet(_luaGcInternal)
                lua.pushValue(1)
                lua.pCall(1, 0)
                lua.pop(1)
                0
            }
            lua.setGlobal("aris__gc")
            if (lua.getMetaField(
                    -1,
                    "__newindex"
                ) == 0
            ) throw NoSuchElementException("Cannot retrieve __newindex metafield")
            lua.setGlobal("aris__newindex")
            if (lua.getMetaField(-1, "__eq") == 0) throw NoSuchElementException("Cannot retrieve __eq metafield")
            lua.setGlobal("aris__eq")
            lua.pop(1)
        }

        lua.push { lua ->
            lua.push(System.currentTimeMillis())
            1
        }
        lua.setGlobal("get_time")

        lua.load(
            """
            local function true_fn() return true end

            function task_sleep(time)
                local current_time = get_time()
                task_yield(function()
                    return (get_time() - current_time) > time 
                end)
            end

            function task_yield(fn) -- the most of use case of parameter `fn` is for coroutine integration
                fn = fn or true_fn
                while true do
                    coroutine.yield()
                    if(fn()) then break end
                end
            end
        """.trimIndent()
        )
        lua.pCall(0, 0)
    }
}