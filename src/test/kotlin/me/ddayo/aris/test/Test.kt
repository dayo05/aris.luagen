package me.ddayo.aris.test

import me.ddayo.aris.ILuaStaticDecl
import me.ddayo.aris.LuaEngine
import me.ddayo.aris.gen.Test1_LuaGenerated.pushLua
import me.ddayo.aris.gen.Test2_LuaGenerated.pushLua
import me.ddayo.aris.gen.TestGenerated
import me.ddayo.aris.luagen.LuaFunction
import me.ddayo.aris.luagen.LuaProvider
import party.iroiro.luajava.Lua
import party.iroiro.luajava.luajit.LuaJit
import kotlin.random.Random

@LuaProvider("TestGenerated")
open class Test1 : ILuaStaticDecl {
    @LuaFunction
    fun f1() {
        println("F1 called")
    }

    override fun toLua(lua: Lua) = pushLua(lua)
}

@LuaProvider
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
class Test2 : Test1(), ILuaStaticDecl {
    @LuaFunction
    fun f2() {
        println("F2 called")
    }

    override fun toLua(lua: Lua) = pushLua(lua)
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
            local d = {}
            -- t2:f2() -- must failed
            while true do
                local a = ""
                for x = 0, 1000 do
                    d[rand(100)] = { a = create_test2() }
                end
                -- print(collectgarbage("count"))
                coroutine.yield()
            end
        """.trimIndent(), "name"
    )

    while (true) {
        engine.loop()
        println(Runtime.getRuntime().totalMemory())
        Thread.sleep(100)
    }
}