# Inheritance support

aris.luagen supports inheritance wow!!

This is example code:
```kotlin
// This code is same with test/me/ddayo/aris/test/Test.kt
package me.ddayo.aris.test

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
    engine.addTask(engine.LuaTask("""
local t = create_test2()
t:f2()
t:f1()
local t2 = create_test1()
t2:f1()
t2:f2() -- must raise exception
""".trimIndent(), "name"))
    engine.loop()
}
```