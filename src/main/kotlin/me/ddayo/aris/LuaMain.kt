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

    fun initLua(lua: Lua) {
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
            local _scheduler = {}
            _scheduler.__index = _scheduler

            local function true_fn() return true end

            -- register new coroutine
            function _scheduler:new(fn, opt)
                local o = {}
                o.opt = opt
                o.execute = true_fn
                setmetatable(o, self)
                o.task = coroutine.create(fn)
                return o
            end

            function _scheduler:resume(...)
                self.execute = nil
                return coroutine.resume(self.task, self, ...)
            end

            function _scheduler:sleep(time)
                local current_time = get_time()
                self.execute = function() return get_time() - current_time > time end
                coroutine.yield(self)
            end

            function _scheduler:yield(fn) -- the most of use case of parameter `fn` is for coroutine integration
                self.execute = fn or true_fn
                coroutine.yield(self)
            end

            Scheduler = _scheduler -- move it to global

            local registered_tasks = {}
            function register_task(script, name)
                if(name == nil) then name = "lua" end
                local fn = load("return function(task)\n" .. script .. "\nend", name, "t", _G)()
                registered_tasks[#registered_tasks+1] = Scheduler:new(fn, { name = name })
            end

            local current_task
            function get_current_task() return current_task end

            function loop()
                for i, v in ipairs(registered_tasks) do
                    if v:execute() then
                        current_task = v
                        local t, s = v:resume()
                        if not t then error("error inside coroutine " .. v.opt.name .. ": " .. s) end
                        if coroutine.status(v.task) == "dead" then registered_tasks[i] = nil end
                    end
                end
            end
        """.trimIndent())
        lua.pCall(0, 0)

        LuaGenerated.initLua(lua)
    }
}