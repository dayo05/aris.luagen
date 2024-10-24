package me.ddayo.aris.test

import me.ddayo.aris.CoroutineProvider
import me.ddayo.aris.ILuaStaticDecl
import me.ddayo.aris.LuaEngine
import me.ddayo.aris.LuaMultiReturn
import me.ddayo.aris.gen.Test1_LuaGenerated
import me.ddayo.aris.gen.Test2_LuaGenerated
import me.ddayo.aris.gen.TestGenerated
import me.ddayo.aris.luagen.LuaFunction
import me.ddayo.aris.luagen.LuaProvider
import party.iroiro.luajava.Lua
import party.iroiro.luajava.luajit.LuaJit
import kotlin.random.Random

@LuaProvider("TestGenerated")
open class Test1 : ILuaStaticDecl by Test1_LuaGenerated {
    @LuaFunction
    fun f1() {
        println("F1 called")
    }

    var a = 0
    @LuaFunction
    fun incr(): LuaMultiReturn {
        a++
        println("New a: $a")
        return LuaMultiReturn(a, Random.nextDouble())
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

class TestEngine(lua: Lua) : LuaEngine(lua) {
    init {
        TestGenerated.initLua(lua)
    }
}

fun main() {
    val engine = TestEngine(LuaJit())
    engine.createTask(
        """
            local t = create_test2()
            t:f2()
            t:f1()
            local t2 = create_test1()
            t2:f1()
            l1, l2 = t:incr()
            print(l1 .. ", " .. l2)
            l1, l2 = t:incr()
            print(l1 .. ", " .. l2)
            print(t:coroutine_test())
            local d = {}
            -- t2:f2() -- must failed
            while true do
                local a = ""
                for x = 0, 1000 do
                    d[rand(100)] = { a = create_test2() }
                end
                print(task:get_task_name() .. ": " .. collectgarbage("count"))
                task_sleep(10000)
            end
        """.trimIndent(), "name"
    )

    while (true) {
        engine.loop()
        // println(Runtime.getRuntime().totalMemory())
        Thread.sleep(100)
    }
}