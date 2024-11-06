package me.ddayo.aris

import me.ddayo.aris.gen.LuaGenerated.LuaTask_LuaGenerated
import me.ddayo.aris.luagen.LuaFunction
import me.ddayo.aris.luagen.LuaProvider
import party.iroiro.luajava.Lua
import party.iroiro.luajava.LuaException

open class LuaEngine(protected val lua: Lua) {
    enum class TaskStatus {
        /**
         * Task loaded to lua engine but never executed yet
         */
        INITIALIZED,

        /**
         * Task failed to loaded
         */
        LOAD_ERROR,

        /**
         * Runtime exception thrown
         */
        RUNTIME_ERROR,

        /**
         * Task is running. This is available for multithreaded environment(unstable)
         */
        RUNNING,

        /**
         * Task is yielded
         */
        YIELDED,

        /**
         * Task is ended
         */
        FINISHED
    }

    init {
        LuaMain.initLua(lua)
    }

    val tasks = mutableListOf<LuaTask>()

    fun loop() {
        for (task in tasks)
            task.loop()
    }

    fun removeAllFinished() {
        tasks.removeAll { it.taskStatus == TaskStatus.FINISHED }
    }

    open fun createTask(code: String, name: String, repeat: Boolean = false) =
        LuaTask(code, name, repeat).also { tasks.add(it) }

    @LuaProvider
    open inner class LuaTask(code: String, val name: String, val repeat: Boolean = false) :
        ILuaStaticDecl by LuaTask_LuaGenerated {
        val engine = this@LuaEngine

        var taskStatus = TaskStatus.INITIALIZED
            protected set

        private lateinit var coroutine: Lua
        private val refIdx: Int // function to executed inside coroutine

        open var isPaused = false

        var errorMessage: StringBuilder = StringBuilder()
            private set

        init {
            if (try {
                    lua.load(
                        """return function(task)
            |    $code
            |end""".trimMargin()
                    )
                    true
                } catch (e: LuaException) {
                    taskStatus = TaskStatus.LOAD_ERROR
                    errorMessage.append(e.message)
                    false
                }
            ) {
                lua.pCall(0, 1)
                refIdx = lua.ref()
                init()
            } else refIdx = -1
        }

        private fun init() {
            coroutine = lua.newThread()
            coroutine.refGet(refIdx) // code
            taskStatus = TaskStatus.INITIALIZED
        }

        fun pullError(): StringBuilder {
            val s = errorMessage
            errorMessage = StringBuilder()
            return s
        }

        private fun resume(arg: Int) {
            try {
                taskStatus = TaskStatus.RUNNING
                if (coroutine.resume(arg)) {
                    coroutine.close()
                    if (repeat) {
                        init()
                    } else taskStatus = TaskStatus.FINISHED
                } else taskStatus = TaskStatus.YIELDED
            } catch (e: LuaException) {
                errorMessage.appendLine(e.message)
                taskStatus = TaskStatus.RUNTIME_ERROR
            }
        }

        fun loop() {
            if (isPaused) return
            if (taskStatus == TaskStatus.INITIALIZED) {
                coroutine.pushJavaObject(this)
                toLua(coroutine)
                resume(1)
            } else if (taskStatus == TaskStatus.YIELDED)
                resume(0)
        }

        // unref code on Java object has been collected by GC
        fun finalize() {
            lua.unref(refIdx)
        }

        open fun remove() {
            tasks.remove(this)
        }

        @LuaFunction("get_task_name")
        @JvmName("get_name")
        fun getName() = name
    }
}
