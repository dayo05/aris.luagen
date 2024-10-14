package me.ddayo.aris.test

import me.ddayo.aris.ILuaStaticDecl
import me.ddayo.aris.LuaEngine
import me.ddayo.aris.gen.Test1_LuaGenerated.pushLua
import me.ddayo.aris.gen.Test2_LuaGenerated.pushLua
import me.ddayo.aris.gen.TestGenerated
import me.ddayo.aris.luagen.LuaFunction
import me.ddayo.aris.luagen.LuaProvider
import party.iroiro.luajava.Lua

@LuaProvider("TestGenerated")
open class Test1: ILuaStaticDecl {
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
}

@LuaProvider("TestGenerated")
class Test2: Test1(), ILuaStaticDecl {
    @LuaFunction
    fun f2() {
        println("F2 called")
    }

    override fun toLua(lua: Lua) = pushLua(lua)
}

class TestEngine: LuaEngine() {
    init {
        TestGenerated.initLua(lua)
    }
}

fun main() {
    val engine = TestEngine()
    engine.addTask(
        LuaEngine.LuaTask(engine, """
        local t = create_test2()
        t:f2()
        t:f1()
        local t2 = create_test1()
        t2:f1()
        t2:f2() -- must failed
    """.trimIndent(), "name"))
    engine.loop()
}