# Inheritance support

aris.luagen supports inheritance wow!!

This is example code:
```kotlin
package me.ddayo.aris.test

@LuaProvider("TestGenerated")
open class Test1: ILuaStaticDecl by Test1_LuaGenerated {
    @LuaFunction
    fun f1() {
        println("F1 called")
    }
}

@LuaProvider
object TestObj {
    @LuaFunction
    fun create_test2() = Test2()
    @LuaFunction
    fun create_test1() = Test1()
}

@LuaProvider("TestGenerated")
class Test2: Test1(), ILuaStaticDecl by Test2_LuaGenerated {
    @LuaFunction
    fun f2() {
        println("F2 called")
    }
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