package me.ddayo.aris.test

import me.ddayo.aris.luagen.LuaEngine
import party.iroiro.luajava.luajit.LuaJit
import kotlin.test.*

class SilentTestEngine(lua: party.iroiro.luajava.Lua, val errors: MutableList<String> = mutableListOf()) :
    LuaEngine(lua, { errors.add(it) }) {
    init {
        me.ddayo.aris.gen.TestGenerated.initEngine(this)
    }
}

class LuaGenTest {
    private lateinit var engine: SilentTestEngine

    @BeforeTest
    fun setup() {
        engine = SilentTestEngine(LuaJit())
    }

    @AfterTest
    fun teardown() {
        engine.dispose()
    }

    private fun runLua(code: String): LuaEngine.LuaCodeTask {
        val task = engine.createTask(code.trimIndent(), "test")
        engine.loop()
        return task
    }

    private fun getLuaNumber(name: String): Double {
        engine.lua.getGlobal(name)
        val v = engine.lua.toNumber(-1)
        engine.lua.pop(1)
        return v
    }

    private fun getLuaString(name: String): String? {
        engine.lua.getGlobal(name)
        val v = engine.lua.toString(-1)
        engine.lua.pop(1)
        return v
    }

    private fun getLuaBoolean(name: String): Boolean {
        engine.lua.getGlobal(name)
        val v = engine.lua.toBoolean(-1)
        engine.lua.pop(1)
        return v
    }

    private fun getLuaObject(name: String): Any? {
        engine.lua.getGlobal(name)
        if (engine.lua.isNil(-1)) {
            engine.lua.pop(1)
            return null
        }
        // ILuaStaticDecl objects have a custom metatable, which makes toJavaObject fail.
        // Swap to the original Java metatable if needed.
        var v = engine.lua.toJavaObject(-1)
        if (v == null) {
            engine.lua.refGet(engine.luaMain._luaGlobalMt)
            engine.lua.setMetatable(-2)
            v = engine.lua.toJavaObject(-1)
        }
        engine.lua.pop(1)
        return v
    }

    private fun assertTaskFinished(task: LuaEngine.LuaCodeTask) {
        assertEquals(LuaEngine.TaskStatus.FINISHED, task.taskStatus,
            "Task should have finished but was ${task.taskStatus}")
    }

    // ===== Static function tests =====

    @Test
    fun staticFunctionCreatesTest1() {
        runLua("result = create_test1()")
        assertIs<Test1>(getLuaObject("result"))
    }

    @Test
    fun staticFunctionCreatesTest2() {
        runLua("result = create_test2()")
        val obj = getLuaObject("result")
        assertIs<Test2>(obj)
        assertIs<Test1>(obj) // Test2 extends Test1
    }

    @Test
    fun staticFunctionWithIntArg() {
        runLua("result = rand(100)")
        val v = getLuaNumber("result").toInt()
        assertTrue(v in -99..99, "rand(100) should return value in -99..99, got $v")
    }

    @Test
    fun staticFunctionWithStringReturn() {
        runLua("result = randstr(10)")
        val v = getLuaString("result")
        assertNotNull(v)
        assertEquals(10, v.length, "randstr(10) should return string of length 10")
    }

    @Test
    fun staticFunctionGetNano() {
        runLua("result = getNano()")
        assertTrue(getLuaNumber("result") > 0)
    }

    @Test
    fun staticFunctionPrint() {
        val task = runLua("""p("hello")""")
        assertTaskFinished(task)
    }

    // ===== Instance method tests =====

    @Test
    fun instanceMethodF1() {
        val task = runLua("""
            local t = create_test1()
            t:f1(42)
        """)
        assertTaskFinished(task)
    }

    @Test
    fun instanceMethodF2OnTest2() {
        val task = runLua("""
            local t = create_test2()
            t:f2()
        """)
        assertTaskFinished(task)
    }

    @Test
    fun instanceMethodTestArisReturnsObject() {
        runLua("""
            local t = getTestAris()
            result = t:test()
        """)
        assertIs<TestAris>(getLuaObject("result"))
    }

    @Test
    fun instanceMethodTestArisVoid() {
        val task = runLua("""
            local t = getTestAris()
            t:testV()
            t:testV()
            t:testV()
        """)
        assertTaskFinished(task)
    }

    // ===== Property getter/setter tests =====

    @Test
    fun propertyDefaultValue() {
        runLua("""
            local t = create_test1()
            result = t:get_aaa()
        """)
        assertEquals(0.0, getLuaNumber("result"))
    }

    @Test
    fun propertySetAndGet() {
        runLua("""
            local t = create_test1()
            t:set_aaa(1557)
            result = t:get_aaa()
        """)
        assertEquals(1557.0, getLuaNumber("result"))
    }

    @Test
    fun propertySetMultipleTimes() {
        runLua("""
            local t = create_test1()
            t:set_aaa(10)
            t:set_aaa(20)
            t:set_aaa(30)
            result = t:get_aaa()
        """)
        assertEquals(30.0, getLuaNumber("result"))
    }

    @Test
    fun propertyIsolatedBetweenInstances() {
        runLua("""
            local t1 = create_test1()
            local t2 = create_test1()
            t1:set_aaa(100)
            t2:set_aaa(200)
            r1 = t1:get_aaa()
            r2 = t2:get_aaa()
        """)
        assertEquals(100.0, getLuaNumber("r1"))
        assertEquals(200.0, getLuaNumber("r2"))
    }

    // ===== Multiple return values =====

    @Test
    fun multiReturnValues() {
        runLua("""
            local t = create_test1()
            r1, r2 = t:incr()
        """)
        assertEquals(1.0, getLuaNumber("r1"))
        // r2 is a random double, just verify it's a number
        assertTrue(getLuaNumber("r2") >= 0.0)
    }

    @Test
    fun multiReturnConsecutiveCalls() {
        runLua("""
            local t = create_test1()
            a1, _ = t:incr()
            a2, _ = t:incr()
            a3, _ = t:incr()
        """)
        assertEquals(1.0, getLuaNumber("a1"))
        assertEquals(2.0, getLuaNumber("a2"))
        assertEquals(3.0, getLuaNumber("a3"))
    }

    @Test
    fun multiReturnFromStaticFunction() {
        runLua("""
            r1, r2 = getTesters()
        """)
        assertIs<TestAris>(getLuaObject("r1"))
        assertIs<TestReflection>(getLuaObject("r2"))
    }

    // ===== Inheritance tests =====

    @Test
    fun test2InheritsF1FromTest1() {
        val task = runLua("""
            local t = create_test2()
            t:f1(99)
        """)
        assertTaskFinished(task)
    }

    @Test
    fun test2InheritsPropertyFromTest1() {
        runLua("""
            local t = create_test2()
            t:set_aaa(777)
            result = t:get_aaa()
        """)
        assertEquals(777.0, getLuaNumber("result"))
    }

    @Test
    fun test2InheritsIncrFromTest1() {
        runLua("""
            local t = create_test2()
            r1, _ = t:incr()
            r2, _ = t:incr()
        """)
        assertEquals(1.0, getLuaNumber("r1"))
        assertEquals(2.0, getLuaNumber("r2"))
    }

    @Test
    fun test2HasOwnAndInheritedMethods() {
        val task = runLua("""
            local t = create_test2()
            t:f1(1)
            t:f2()
            t:set_aaa(42)
            r1 = t:get_aaa()
            r2, _ = t:incr()
        """)
        assertTaskFinished(task)
        assertEquals(42.0, getLuaNumber("r1"))
        assertEquals(1.0, getLuaNumber("r2"))
    }

    @Test
    fun test1CannotCallTest2Methods() {
        runLua("""
            local t = create_test1()
            ok, err = pcall(function() t:f2() end)
            result = ok
        """)
        assertFalse(getLuaBoolean("result"), "Test1 should not have f2 method")
    }

    // ===== Multiple arguments tests =====

    @Test
    fun multipleIntAndObjectArgs() {
        val task = runLua("""
            local t1 = create_test1()
            local t2 = create_test1()
            t1:multiArgs(1, 2, t1, t2)
        """)
        assertTaskFinished(task)
    }

    @Test
    fun passingObjectBetweenInstances() {
        val task = runLua("""
            local t1 = create_test1()
            local t2 = create_test1()
            t1:comp(t2)
            t2:comp(t1)
        """)
        assertTaskFinished(task)
    }

    // ===== Lua callback (LuaFunc) tests =====

    @Test
    fun luaCallbackReceivesArguments() {
        runLua("""
            local t = create_test1()
            result = 0
            t:hook(function(a, b, c) result = a + b + c end)
        """)
        assertEquals(6.0, getLuaNumber("result"))
    }

    @Test
    fun luaCallbackModifiesUpvalue() {
        runLua("""
            local t = create_test1()
            local captured = {}
            t:hook(function(a, b, c)
                captured[1] = a
                captured[2] = b
                captured[3] = c
            end)
            r1 = captured[1]
            r2 = captured[2]
            r3 = captured[3]
        """)
        assertEquals(1.0, getLuaNumber("r1"))
        assertEquals(2.0, getLuaNumber("r2"))
        assertEquals(3.0, getLuaNumber("r3"))
    }

    @Test
    fun functionTestAcceptsCallback() {
        val task = runLua("""
            functionTest(function(a, b, c) end)
        """)
        assertTaskFinished(task)
    }

    // ===== @RetrieveEngine tests =====

    @Test
    fun retrieveEngineInjectsProperly() {
        val task = runLua("""
            local t = create_test1()
            t:getLua()
        """)
        assertTaskFinished(task)
    }

    @Test
    fun retrieveEngineCanModifyGlobals() {
        runLua("""
            local t = create_test1()
            t:getLua()
            result = test1
        """)
        // getLua() sets test1 = 5
        assertEquals(5.0, getLuaNumber("result"))
    }

    // ===== Coroutine tests =====

    @Test
    fun coroutineStartsAndYields() {
        val task = engine.createTask("""
            local t = create_test2()
            r1, r2, r3 = t:coroutine_test()
        """.trimIndent(), "coroutine_test")
        engine.loop()
        // coroutine_test yields for 2 seconds, so should be YIELDED after first loop
        assertEquals(LuaEngine.TaskStatus.YIELDED, task.taskStatus)
    }

    @Test
    fun coroutineCompletesAfterTimeout() {
        val task = engine.createTask("""
            local t = create_test2()
            r1, r2, r3 = t:coroutine_test()
        """.trimIndent(), "coroutine_test")

        val start = System.currentTimeMillis()
        while (task.taskStatus != LuaEngine.TaskStatus.FINISHED) {
            engine.loop()
            if (System.currentTimeMillis() - start > 5000) break // safety timeout
            Thread.sleep(50)
        }
        assertTaskFinished(task)
        assertEquals(1.0, getLuaNumber("r1"))
        assertEquals(2.0, getLuaNumber("r2"))
        assertEquals(3.0, getLuaNumber("r3"))
    }

    // ===== Task management tests =====

    @Test
    fun taskFinishesAfterSimpleCode() {
        val task = runLua("local x = 1 + 2")
        assertTaskFinished(task)
    }

    @Test
    fun taskReportsLoadError() {
        val task = engine.createTask("this is not valid lua !!!", "bad")
        assertEquals(LuaEngine.TaskStatus.LOAD_ERROR, task.taskStatus)
        assertTrue(engine.errors.any { "'=' expected" in it }, "Should capture syntax error message")
    }

    @Test
    fun multipleTasksExecute() {
        engine.createTask("g_a = 10", "t1")
        engine.createTask("g_b = 20", "t2")
        // Tasks auto-remove on finish, which can skip the next task in the same loop
        engine.loop()
        engine.loop()
        assertEquals(10.0, getLuaNumber("g_a"))
        assertEquals(20.0, getLuaNumber("g_b"))
    }

    @Test
    fun tasksExecuteInOrder() {
        engine.createTask("g_order = 1", "t1")
        engine.createTask("g_order = g_order + 10", "t2")
        engine.loop()
        engine.loop()
        assertEquals(11.0, getLuaNumber("g_order"))
    }

    @Test
    fun finishedTasksAutoRemove() {
        engine.createTask("local x = 1", "t1")
        engine.createTask("local x = 2", "t2")
        engine.loop()
        engine.loop()
        assertEquals(0, engine.tasks.size)
    }

    @Test
    fun yieldingTaskStaysInList() {
        engine.createTask("task_yield(function() return false end)", "yielding")
        engine.createTask("local x = 1", "finishing")
        engine.loop()
        // Finished task auto-removed, yielding task stays
        assertEquals(1, engine.tasks.size)
        assertEquals("yielding", engine.tasks[0].name)
    }

    // ===== Error handling tests =====

    @Test
    fun pcallCatchesWrongArgCount() {
        runLua("""
            local t = create_test1()
            ok, err = pcall(function() t:f1() end)
            result = ok
        """)
        assertFalse(getLuaBoolean("result"))
    }

    @Test
    fun pcallCatchesMissingMethod() {
        runLua("""
            local t = create_test1()
            ok, err = pcall(function() t:nonexistent_method() end)
            result = ok
        """)
        assertFalse(getLuaBoolean("result"))
    }

    // ===== Object identity and type tests =====

    @Test
    fun javaObjectTypeIsUserdata() {
        runLua("""
            local t = create_test1()
            result = type(t)
        """)
        assertEquals("userdata", getLuaString("result"))
    }

    @Test
    fun differentInstancesAreDistinct() {
        runLua("""
            local t1 = create_test1()
            local t2 = create_test1()
            t1:set_aaa(111)
            t2:set_aaa(222)
            r1 = t1:get_aaa()
            r2 = t2:get_aaa()
        """)
        assertEquals(111.0, getLuaNumber("r1"))
        assertEquals(222.0, getLuaNumber("r2"))
    }

    // ===== Stress / GC tests =====

    @Test
    fun manyObjectCreationsDoNotCrash() {
        val task = runLua("""
            for i = 1, 1000 do
                local t = create_test1()
                t:set_aaa(i)
            end
        """)
        assertTaskFinished(task)
    }

    @Test
    fun manyCallbacksDoNotCrash() {
        val task = runLua("""
            for i = 1, 100 do
                functionTest(function() end)
            end
        """)
        assertTaskFinished(task)
    }

    // ===== Task name test =====

    @Test
    fun taskNameAccessibleFromKotlin() {
        val task = engine.createTask("local x = 1", "my_task_name")
        assertEquals("my_task_name", task.name)
    }

    @Test
    fun luaTaskMethodViaBinding() {
        // LuaTask.getName is bound as get_task_name via @LuaFunction
        // Test that the generated binding for LuaTask exists by checking
        // the metatable key is registered after engine init
        assertTrue(engine.inner.containsKey("aris_me_ddayo_aris_luagen_LuaEngine_LuaTask_mt"))
    }
}
