package me.ddayo.aris

import party.iroiro.luajava.Lua

open class LuaEngine(protected val lua: Lua) {
    init {
        LuaMain.initLua(lua)
    }

    val tasks = mutableListOf<LuaTask>()

    fun loop() {
        val toRemove = mutableListOf<LuaTask>()
        for(task in tasks) {
            task.loop()
            if(!task.running) toRemove.add(task)
        }
        tasks.removeAll(toRemove)
    }

    open fun createTask(code: String, name: String, repeat: Boolean = false) = tasks.add(LuaTask(code, name, repeat))

    open inner class LuaTask(code: String, val name: String, val repeat: Boolean = false) {
        val engine = this@LuaEngine

        var running = false
            private set
        private lateinit var coroutine: Lua
        private val refIdx: Int // function to executed inside coroutine

        open var isPaused = false

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

        open fun remove() {
            tasks.remove(this)
        }
    }
}
