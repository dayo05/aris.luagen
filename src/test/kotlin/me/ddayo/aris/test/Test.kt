package me.ddayo.aris.test

import me.ddayo.aris.CoroutineProvider
import me.ddayo.aris.ILuaStaticDecl
import me.ddayo.aris.LuaEngine
import me.ddayo.aris.LuaMultiReturn
import me.ddayo.aris.gen.TestGenerated.Test1_LuaGenerated
import me.ddayo.aris.gen.TestGenerated.Test2_LuaGenerated
import me.ddayo.aris.gen.TestGenerated.TestAris_LuaGenerated
import me.ddayo.aris.gen.TestGenerated
import me.ddayo.aris.luagen.LuaFunction
import me.ddayo.aris.luagen.LuaInstance
import me.ddayo.aris.luagen.LuaProvider
import party.iroiro.luajava.Lua
import party.iroiro.luajava.luajit.LuaJit
import kotlin.random.Random

@LuaProvider("TestGenerated")
open class Test1 : ILuaStaticDecl by Test1_LuaGenerated {
    @LuaFunction
    fun f1(x: Int) {
        println("F1 called with $x")
    }

    @LuaFunction
    fun getLua(@LuaInstance instance: Lua) {
        instance.getGlobal("test1")
        println(instance.get().type())
        instance.push(5)
        instance.setGlobal("test1")
    }

    var a = 0
    @LuaFunction
    fun incr(): LuaMultiReturn {
        a++
        println("New a: $a")
        return LuaMultiReturn(a, Random.nextDouble())
    }

    @LuaFunction
    fun comp(other: Test1) {
        println("Compare! this: $a, other: ${other.a}")
    }

    @LuaFunction
    fun multiArgs(a: Int, b: Int, c: Test1, d: Test1) {
        println("multiArgs($a, $b, $c)")
    }
}

@LuaProvider("TestGenerated")
object TestObj {
    @LuaFunction
    fun create_test2() = Test2()

    @LuaFunction
    fun create_test1() = Test1()

    @LuaFunction
    fun rand(x: Int) = Random.nextInt() % x

    @LuaFunction
    fun randstr(l: Int) = (0 until l).joinToString("") { (rand(50) + '0'.code).toChar().toString() }

    @LuaFunction
    fun print(x: String) = println(x)
}

@LuaProvider("TestGenerated")
class Test2 : Test1(), ILuaStaticDecl by Test2_LuaGenerated, CoroutineProvider {
    @LuaFunction
    fun f2() {
        println("F2 called")
    }

    @LuaFunction("coroutine_test")
    fun coroutineTest() = coroutine {
        val current = System.currentTimeMillis()
        println("A")
        yieldUntil { current + 2000 < System.currentTimeMillis() } // wait 2 sec
        println("B")
        breakTask(1, 2, 3)
    }
}

@LuaProvider("TestGenerated")
class TestAris: ILuaStaticDecl by TestAris_LuaGenerated {
    var a = 0
    @LuaFunction
    fun test(): TestAris {
        a++
        return this
    }
}

class TestReflection {
    var a = 0
    fun test(): TestReflection {
        a++
        return this
    }
}

@LuaProvider("TestGenerated")
object SpeedTest {
    @LuaFunction
    fun getTesters() = LuaMultiReturn(TestAris(), TestReflection())

    @LuaFunction
    fun getNano() = System.nanoTime()
}

class TestEngine(lua: Lua) : LuaEngine(lua) {
    init {
        TestGenerated.initLua(lua)
    }
}

fun main() {
    val engine = TestEngine(LuaJit())
    engine.createTask(
        """
            local tester = create_test2()
            tester:multiArgs(1, 2, tester, tester)
            local k = 0
            for i=1,1000 do k = k + i end
            
            local aris, reflection = getTesters()
            
            print("begin")
            
            -- local ep = 10000000
            -- Original: 11220988583
            --     Aris: 10608901500
            
            local ep = 100
            -- Original: 2412125
            --     Aris: 402875
            local n
            
            n = getNano()
            for i=1,ep do reflection = reflection:test() end
            print("Original: " .. getNano() - n)
            
            n = getNano() 
            for i=1,ep do aris = aris:test() end
            print("Aris: " .. getNano() - n)
            
            local t = create_test2()
            t:f2()
            t:f1(13)
            local t2 = create_test1()
            t2:f1(55)
            l1, l2 = t:incr()
            print(l1 .. ", " .. l2)
            l1, l2 = t:incr()
            print(l1 .. ", " .. l2)
            print(t:coroutine_test())
            local d = {}
            t:comp(t2)
            t2:comp(t)
            print(type(t))
            t:getLua()
            t:getLua()
            -- t2:f2() -- must failed
            while true do
                local a = ""
                for x = 0, 1000 do
                    d[rand(100)] = { a = create_test2() }
                end
                print(task:get_task_name() .. ": " .. collectgarbage("count"))
                print(test1)
                task_sleep(10000)
            end
        """.trimIndent(), "name"
    )

    val task = engine.tasks.first()
    if(task.errorMessage.isNotEmpty())
        println(task.errorMessage)
    while (true) {
        engine.loop()
        if(task.errorMessage.isNotEmpty())
            println(task.pullError())

        // println(Runtime.getRuntime().totalMemory())
        Thread.sleep(100)
    }
}