package me.ddayo.aris

import me.ddayo.aris.gen.LuaGenerated
import party.iroiro.luajava.Lua

object LuaMain {
    fun <T> push(lua: Lua, it: T): Int {
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
                it.toLua(lua)
                return 1
            }
            is LuaMultiReturn -> {
                it.luaFn(lua)
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

    internal fun initLua(lua: Lua) {
        lua.openLibraries()

        run {
            lua.pushJavaObject(Any())
            lua.getMetatable(-1)
            lua.setGlobal("aris__obj_mt")

            if(lua.getMetaField(-1, "__gc") == 0) throw NoSuchElementException("Cannot retrieve __gc metafield")
            lua.setGlobal("aris__gc_internal")
            lua.push { lua ->
                // lua.pushTable(...)
                lua.getGlobal("aris__obj_mt")
                lua.setMetatable(-2)
                lua.getGlobal("aris__gc_internal")
                lua.pushValue(1)
                lua.pCall(1, 0)
                lua.pop(1)
                0
            }
            lua.setGlobal("aris__gc")
            if(lua.getMetaField(-1, "__newindex") == 0) throw NoSuchElementException("Cannot retrieve __newindex metafield")
            lua.setGlobal("aris__newindex")
            if(lua.getMetaField(-1, "__eq") == 0) throw NoSuchElementException("Cannot retrieve __eq metafield")
            lua.setGlobal("aris__eq")
            lua.pop(1)
        }

        lua.push { lua ->
            lua.push(System.currentTimeMillis())
            1
        }
        lua.setGlobal("get_time")

        lua.load("""
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
        """.trimIndent())
        lua.pCall(0, 0)

        LuaGenerated.initLua(lua)
    }
}