package me.ddayo.aris

import me.ddayo.aris.gen.LuaGenerated.LuaTask_LuaGenerated
import me.ddayo.aris.luagen.LuaFunction
import me.ddayo.aris.luagen.LuaProvider
import party.iroiro.luajava.Lua
import party.iroiro.luajava.LuaException
import party.iroiro.luajava.luajit.LuaJitNatives

open class LuaEngine(protected val lua: Lua, val preventInfinityLoop: Boolean = true, private val errorMessageHandler: (s: String) -> Unit = {}) {
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

    var loopCount = 0L
        private set

    fun loop() {
        for (task in tasks)
            task.loop()
        loopCount++
    }

    private var lastLoop = -1L
    fun testInfinite() = (lastLoop == loopCount).also {
        lastLoop = loopCount
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

        init {
            val codeBuilder = StringBuilder()
            codeBuilder.append("return function(task)")
            if(preventInfinityLoop)
                codeBuilder.append("""
    debug.sethook(function()
        if task:test_execution() then 
            error("Infinite loop detected!! Please yield frequently.") 
        end 
    end, "", 1000000)
                """.trimIndent())
            codeBuilder.appendLine(code)
            codeBuilder.append("end")

            if (try {
                    lua.load(codeBuilder.toString().trimMargin())
                    true
                } catch (e: LuaException) {
                    taskStatus = TaskStatus.LOAD_ERROR
                    errorMessageHandler((e.message ?: "No message provided") + "\n" + e.stackTraceToString())
                    false
                }
            ) {
                lua.pCall(0, 1)
                refIdx = lua.ref()
                init()
            } else refIdx = -1
        }

        private var resumeParam = 0
        private fun init() {
            coroutine = lua.newThread()
            coroutine.refGet(refIdx) // code
            coroutine.pushJavaObject(this)
            LuaTask_LuaGenerated.toLua(coroutine)
            resumeParam = 1
            taskStatus = TaskStatus.INITIALIZED
        }

        private fun resume(arg: Int) {
            try {
                taskStatus = TaskStatus.RUNNING
                if (!coroutine.resume(arg)) {
                    coroutine.close()
                    if (repeat) {
                        init()
                    } else taskStatus = TaskStatus.FINISHED
                } else taskStatus = TaskStatus.YIELDED
            } catch (e: LuaException) {
                errorMessageHandler((e.message ?: "No message provided") + "\n" + e.stackTraceToString())
                taskStatus = TaskStatus.RUNTIME_ERROR
            }
        }

        fun loop() {
            if (isPaused) return
            if (taskStatus != TaskStatus.YIELDED && taskStatus != TaskStatus.INITIALIZED) return
            resume(resumeParam)
            resumeParam = 0
        }

        // unref code on Java object has been collected by GC
        fun finalize() {
            lua.unref(refIdx)
        }

        open fun remove() {
            tasks.remove(this)
        }

        fun restart() {
            coroutine.close()
            init()
        }

        @LuaFunction("get_task_name")
        @JvmName("get_name")
        fun getName() = name

        @LuaFunction("test_execution")
        fun testExecution() = engine.testInfinite()
    }
}
