package me.ddayo.aris.luagen

import me.ddayo.aris.gen.LuaGenerated
import party.iroiro.luajava.Lua
import party.iroiro.luajava.LuaException
import java.lang.ref.ReferenceQueue


open class LuaEngine(val lua: Lua, private val errorMessageHandler: (s: String) -> Unit = {}) {
    enum class TaskStatus {
        UNINITIALIZED,
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

    val inner = mutableMapOf<String, Int>() // using this instead of Lua's way because this is (maybe) faster.


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
    fun ref(ref: ArisPhantomReference) {
        refs.add(ref)
        refStatus[ref.ref] = refStatus.getOrDefault(ref.ref, 0) + 1
    }

    private val arisRefQueue = ReferenceQueue<Any>()

    val tasks = mutableListOf<LuaTask>()

    var currentTask: LuaTask? = null
        private set

    val refStatus = mutableMapOf<Int, Int>()

    fun loop() {
        if(isDisposed)
            throw IllegalStateException("Trying to loop disposed engine")

        var taskIdx = 0
        while(taskIdx < tasks.size) {
            val task = tasks[taskIdx]
            currentTask = task
            task.loop()
            taskIdx++
        }

        currentTask = null
        while(true) {
            val ref = ((arisRefQueue.poll() ?: break) as ArisPhantomReference)
            refs.remove(ref)
            refStatus[ref.ref] = refStatus[ref.ref]!! - 1
            if(refStatus[ref.ref]!! == 0) continue
            if(ref.ref == -1) continue
            lua.unref(ref.ref)
        }
    }

    var isDisposed = false
        private set

    fun dispose() {
        isDisposed = true
        lua.close()
    }

    fun removeAllFinished() {
        tasks.removeAll { it.taskStatus == TaskStatus.FINISHED }
    }

    open fun createTask(code: String, name: String, repeat: Boolean = false) =
        LuaCodeTask(code, name, repeat).also {
            it.init()
            tasks.add(it)
        }

    @LuaProvider
    open inner class LuaTask(val name: String, val repeat: Boolean = false): ILuaStaticDecl by LuaGenerated.LuaTask_LuaGenerated {
        val engine = this@LuaEngine
        var taskStatus = TaskStatus.UNINITIALIZED
            protected set

        lateinit var coroutine: Lua
            private set

        var refIdx: Int = -1
            set(value) {
                if(field != -1)
                    throw Exception("Cannot rewrite refIdx")
                field = value
            }

        open fun init() {
            if(refIdx == -1)
                errorMessageHandler("Cannot init with refIdx -1")
            else {
                coroutine = lua.newThread()
                coroutine.refGet(refIdx)
                if(!coroutine.isFunction(-1))
                    errorMessageHandler("Given data is not a function")
                taskStatus = TaskStatus.INITIALIZED
                engine.ref(ArisPhantomReference(this, null, refIdx, arisRefQueue))
            }
        }

        open var isPaused = false

        var initArgc = 0
            set(value) {
                if(field != 0) throw IllegalStateException("Cannot rewrite initArgc")
                field = value
            }

        fun loop() {
            if (isPaused) return
            if (taskStatus != TaskStatus.YIELDED && taskStatus != TaskStatus.INITIALIZED) return
            try {
                val argc = if(taskStatus == TaskStatus.INITIALIZED) initArgc else 0
                taskStatus = TaskStatus.RUNNING
                if (!coroutine.resume(argc)) {
                    coroutine.close()
                    if (repeat) {
                        init()
                    } else {
                        taskStatus = TaskStatus.FINISHED
                        engine.tasks.remove(this)
                    }
                } else taskStatus = TaskStatus.YIELDED
            } catch (e: LuaException) {
                errorMessageHandler((e.message ?: "No message provided") + "\n" + e.stackTraceToString())
                taskStatus = TaskStatus.RUNTIME_ERROR
            }
        }

        open fun remove() {
            tasks.remove(this)
        }

        fun restart() {
            coroutine.close()
            init()
        }

        fun ref(self: Any, ref: Int) {
            engine.ref(ArisPhantomReference(self, this, ref, arisRefQueue))
        }

        @LuaFunction("get_task_name")
        @JvmName("get_name")
        fun getName() = name
    }

    @LuaProvider
    open inner class LuaCodeTask(code: String, name: String, repeat: Boolean = false) :
        LuaTask(name, repeat), ILuaStaticDecl by LuaGenerated.LuaCodeTask_LuaGenerated {

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
            }
        }
    }
}
