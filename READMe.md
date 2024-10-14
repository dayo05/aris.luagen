# Project-AR15.luagen READMe

### This project is WIP and may contain breaking changes on future release a lot!!!

---

## Introduction
Project AR15.luagen is static source generation for luajava **without** using reflection

## Getting Start
### Installation
#### Using git submodule 
> I have a plan to support maven but the priority is low for now

1. Type `git submodule add https://github.com/dayo05/aris.luagen` to add this project as submodule
2. Inside global settings.gradle, append `includeBuild("aris.luagen")` on last
3. Add KSP gradle plugin inside plugins block of build.gradle:
```kotlin
id("com.google.devtools.ksp") version "2.0.0-1.0.21" // version of ksp can dependent on your kotlin version
```
4. Inside dependencies block of build.gradle, append following code to import this project:
```kotlin
implementation("me.ddayo:aris.luagen")
ksp("me.ddayo:ap")
```
### Adding functions
```kotlin
@LuaProvier("MyGenerated") // specify generated class name here. You can also set default name at ksp option
object MyFunctions {
    @LuaFunction // this creates lua function as same name with kotlin side
    fun function1() {
        // do some stuff for function1
    }
    
    @LuaFunction(name = "custom_function") // this overwrites the name of function
    fun function2() {
        // do some stuff for custom_function
    }
    
    @LuaFunction("create_kotlin_class")
    fun function3() = MyKotlinClass()
}

@LuaProvider("MyGenerated")
class MyKotlinClass: IStaticDecl {
    @LuaFunction
    fun myFUnction(param: Int) {
        // do some stuff for myFUnction
    }
    
    // pushLua function will be generated, but you should explicitly mention that here
    // pushLua will be generated only if class contains more than one LuaFunction annotated method
    override fun toLua(lua: Lua) = pushLua(lua)
}
```

### Using Engine
```kotlin
class MyEngine: LuaEngine() {
    init {
        MyGenerated.initLua(lua)
    }
}

class Main {
    fun main() {
        val engine = MyEngine()
        engine.addTask(engine.LuaTask("""
        -- your code here
        local a = create_kotlin_class()
        a:myFUnction(1) -- invoke a.myFUnction with parameter 1
        -- a.myFUnction(1) does not works because the function accepts self object as first argument
        
        coroutine.yield() -- this exists from lua context
        
        custom_function() -- this executed on second loop
        """))
        engine.loop() // execute function
        engine.loop() // resume lua code execution
    }
}
```
> Make sure you have added lua native library before running the code. This project does not ship the lua native

## Pros and Cons

### Pros
1. Because this does not use reflection so it is fast after initialization
2. You can hide some public functions on lua side
3. Advanced task API works with lua coroutine

### Cons
1. This need extra initialization
2. I hacked JNI side of luajava for some features so it can be unstable
> I am tracking some issue on original luajava to make it stable as I can :)

## Known issue
1. GC does not work fine now. I am fixing this with highest priority.
