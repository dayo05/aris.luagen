package me.ddayo.aris

import party.iroiro.luajava.AbstractLua
import party.iroiro.luajava.Lua
import party.iroiro.luajava.luajit.LuaJit
import party.iroiro.luajava.value.LuaValue

open class LuaEngine {
    protected val lua = LuaJit()
    init {
        LuaMain.initLua(lua)
    }

    private val tasks = mutableListOf<LuaTask>()
    fun addTask(task: LuaTask) {
        tasks.add(task)
    }

    fun loop() {
        val toRemove = mutableListOf<LuaTask>()
        for(task in tasks) {
            task.loop()
            if(!task.running) toRemove.add(task)
        }
        tasks.removeAll(toRemove)
    }

    class LuaTask(val engine: LuaEngine, code: String, val name: String, val repeat: Boolean = false) {
        var running = false
            private set
        private lateinit var coroutine: AbstractLua
        private val refIdx: Int // function to executed inside coroutine
        private var yieldRule: LuaValue? = null

        init {
            engine.lua.load("""return function(task)
                |    $code
                |end""".trimMargin())
            engine.lua.pCall(0, 1)
            refIdx = engine.lua.ref()

            init()
        }

        private fun init() {
            coroutine = engine.lua.newThread()
            coroutine.refGet(refIdx) // code
        }

        fun loop() {
            if(yieldRule?.call()?.firstOrNull()?.toJavaObject() as? Boolean == false) return
            running = true
            coroutine.pushJavaObject(this)
            if(!coroutine.resume(1)) {
                coroutine.close()
                if(repeat) {
                    init()
                }
                else running = false
                return
            }
            if(coroutine.top != 0) { // new yield rule exists
                yieldRule = coroutine.get().also {
                    if(it.type() != Lua.LuaType.FUNCTION)
                        throw Exception("Yielded value must be the function")
                }
            }
        }

        // unref code on Java object has been collected by GC
        fun finalize() {
            engine.lua.unref(refIdx)
        }
    }
}
