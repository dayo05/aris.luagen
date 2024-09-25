package me.ddayo.aris

import me.ddayo.aris.gen.LuaGenerated
import party.iroiro.luajava.Lua

object LuaMain {
    fun <T> push(lua: Lua, it: T) {
        when (it) {
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

    internal fun initLua(lua: Lua) {
        lua.push { lua ->
            val r = lua.get().toJavaObject() as? LuaMultiReturn
            r?.luaFn(lua) ?: 0
        }
        lua.setGlobal("resolve_mrt")

        run {
            lua.pushJavaObject(Any())
            lua.getMetatable(-1)
            lua.setGlobal("aris__obj_mt")

            if(lua.getMetaField(-1, "__gc") == 0) throw NoSuchElementException("Cannot retrieve __gc metafield")
            lua.setGlobal("aris__gc")
            if(lua.getMetaField(-1, "__newindex") == 0) throw NoSuchElementException("Cannot retrieve __newindex metafield")
            lua.setGlobal("aris__newindex")
            if(lua.getMetaField(-1, "__eq") == 0) throw NoSuchElementException("Cannot retrieve __eq metafield")
            lua.setGlobal("aris__eq")
            lua.pop(1)
        }

        lua.load("""
            local function true_fn() return true end

            function task_sleep(time)
                local current_time = get_time()
                coroutine.yield(function() return get_time() - current_time > time end)
            end

            function task_yield(fn) -- the most of use case of parameter `fn` is for coroutine integration
                coroutine.yield(fn or true_fn)
            end
        """.trimIndent())
        lua.pCall(0, 0)

        LuaGenerated.initLua(lua)
    }
}