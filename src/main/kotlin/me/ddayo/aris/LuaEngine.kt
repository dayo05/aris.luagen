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

    val tasks = mutableListOf<LuaTask>()

    fun createTask(code: String, name: String, repeat: Boolean = false): LuaTask {
        val task = LuaTask(code, name, repeat)
        tasks.add(task)
        return task
    }

    fun loop() {
        val toRemove = mutableListOf<LuaTask>()
        for(task in tasks) {
            task.loop()
            if(!task.running) toRemove.add(task)
        }
        tasks.removeAll(toRemove)
    }

    inner class LuaTask(code: String, val name: String, val repeat: Boolean = false) {
        var running = false
            private set
        private lateinit var coroutine: AbstractLua
        private val refIdx: Int // function to executed inside coroutine

        public var isPaused = false

        init {
            lua.load("""return function(task)
                |    $code
                |end""".trimMargin())
            lua.pCall(0, 1)
            refIdx = lua.ref()

            init()
        }

        private fun init() {
            coroutine = lua.newThread()
            coroutine.refGet(refIdx) // code
        }

        fun loop() {
            running = true
            if(isPaused) return
            if(!coroutine.resume(0)) {
                coroutine.close()
                if(repeat) {
                    init()
                }
                else running = false
                return
            }
        }

        // unref code on Java object has been collected by GC
        fun finalize() {
            lua.unref(refIdx)
        }
    }
}
