package me.ddayo.aris

import me.ddayo.aris.gen.LuaGenerated
import me.ddayo.aris.gen.LuaGenerated.LuaTask_LuaGenerated
import me.ddayo.aris.luagen.LuaFunction
import me.ddayo.aris.luagen.LuaProvider
import party.iroiro.luajava.Lua
import party.iroiro.luajava.LuaException
import java.lang.ref.ReferenceQueue


open class LuaEngine(val lua: Lua, private val errorMessageHandler: (s: String) -> Unit = {}) {
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

    val inner = mutableMapOf<String, Int>()


    // this is safe due initLua only tries to get `lua` instance this time.
    val luaMain = LuaMain(this)

    init {
        lua.push { lua ->
            currentTask?.toLua(this, lua) ?: run { lua.pushNil() }
            1
        }
        lua.setGlobal("get_current_task")
        LuaGenerated.initEngine(this)
    }

    private val refs = mutableListOf<ArisPhantomReference>()
    private val arisRefQueue = ReferenceQueue<Any>()

    val tasks = mutableListOf<LuaTask>()

    var currentTask: LuaTask? = null
        private set

    fun loop() {
        for (task in tasks) {
            currentTask = task
            task.loop()
        }
        currentTask = null
        while(true) {
            val ref = ((arisRefQueue.poll() ?: break) as ArisPhantomReference)
            refs.remove(ref)
            if(ref.ref == -1) continue
            ref.task?.let {
                it.externalRefCount--
            }
            lua.unref(ref.ref)
        }
    }

    @ReferenceMayKeepAlive
    fun removeAllFinished() {
        tasks.removeAll { it.taskStatus == TaskStatus.FINISHED && it.externalRefCount == 0 }
    }

    open fun createTask(code: String, name: String, repeat: Boolean = false) =
        LuaTask(code, name, repeat).also { tasks.add(it) }

    @LuaProvider
    open inner class LuaTask(code: String, val name: String, val repeat: Boolean = false) :
        ILuaStaticDecl by LuaTask_LuaGenerated {
        val engine = this@LuaEngine

        var taskStatus = TaskStatus.INITIALIZED
            protected set

        lateinit var coroutine: Lua
            private set

        var externalRefCount = 0

        private val refIdx: Int // function to executed inside coroutine

        open var isPaused = false

        init {
            val codeBuilder = StringBuilder()
            codeBuilder.append("return function()")
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
            externalRefCount = 0
            coroutine = lua.newThread()
            coroutine.refGet(refIdx) // code
            resumeParam = 0
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
        private val ref = refIdx
        init {
            refs.add(ArisPhantomReference(this, null, ref, arisRefQueue))
        }

        @ReferenceMayKeepAlive
        open fun remove() {
            if(externalRefCount != 0)
                errorMessageHandler("Task $name removed with remaining $externalRefCount references")
            tasks.remove(this)
        }

        @ReferenceMayKeepAlive
        fun restart() {
            if(externalRefCount != 0)
                errorMessageHandler("Task $name restarted with remaining $externalRefCount references")
            coroutine.close()
            init()
        }

        fun ref(self: Any, ref: Int) {
            externalRefCount++
            refs.add(ArisPhantomReference(self, this, ref, arisRefQueue))
        }

        @LuaFunction("get_task_name")
        @JvmName("get_name")
        fun getName() = name
    }
}
