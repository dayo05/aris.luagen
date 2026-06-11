# Project-AR15.luagen READMe

`aris.luagen` is a Kotlin/JVM source-generation bridge for exposing Kotlin code to Lua through `party.iroiro.luajava`. It uses KSP to generate static binding code from annotations, avoiding reflection for normal Lua dispatch after initialization.

The project has two modules:

- Root module: runtime API used by applications at execution time.
- `ap`: KSP annotation processor that scans annotated Kotlin declarations and generates Lua binding code.

The project is currently a work in progress and may introduce breaking changes.

## Requirements

- Kotlin/JVM.
- Java toolchain 17.
- Gradle.
- KSP matching the Kotlin version used by the consuming project.
- LuaJava runtime. This repository depends on `party.iroiro.luajava:luajit:4.0.2`.
- Lua native binaries at runtime. This project does not ship native Lua binaries for consumers.

The test configuration uses:

```kotlin
implementation("party.iroiro.luajava:luajit:4.0.2")
testRuntimeOnly("party.iroiro.luajava:luajit-platform:4.0.2:natives-desktop")
```

## Installation

The project is intended to be included as a Gradle composite build or submodule.

Add the repository as a submodule:

```bash
git submodule add https://github.com/dayo05/aris.luagen
```

Include it from the consuming build:

```kotlin
includeBuild("aris.luagen")
```

Apply KSP in the consuming module:

```kotlin
plugins {
    id("com.google.devtools.ksp") version "<version matching your Kotlin version>"
}
```

Add runtime and processor dependencies:

```kotlin
dependencies {
    implementation("me.ddayo:aris.luagen")
    ksp("me.ddayo:ap")
}
```

If generated class names should default to something other than `LuaGenerated`, pass the processor option:

```kotlin
ksp {
    arg("default_class_name", "MyGenerated")
}
```

Other processor options:

```kotlin
ksp {
    arg("package_name", "me.example.generated")
    arg("export_lua", "true")
    arg("export_doc", "true")
}
```

- `package_name`: package for generated Kotlin bindings. The default is `me.ddayo.aris.gen`.
- `export_lua`: writes generated Lua dispatcher code to a `.lua` file when set to `true`.
- `export_doc`: writes generated Markdown API signatures to a `.md` file when set to `true`.

## Core Concepts

`@LuaProvider` marks a Kotlin `class` or `object` as a binding source.

`@LuaFunction` marks a function to export to Lua.

`@LuaProperty` marks a property to export through generated `get_<name>` and, for mutable properties, `set_<name>` functions.

`ILuaStaticDecl` is implemented by Kotlin classes that should receive generated Lua metatables. Concrete classes usually delegate it to the generated companion object.

`LuaEngine` owns the Lua state, generated binding initialization, task execution, coroutine resumption, reference tracking, and disposal.

## Exporting Static Functions

Use an `object` provider for global Lua functions:

```kotlin
@LuaProvider("GameBindings")
object GameApi {
    @LuaFunction
    fun print_message(message: String) {
        println(message)
    }

    @LuaFunction("random_int")
    fun randomInt(max: Int): Int {
        return kotlin.random.Random.nextInt(max)
    }
}
```

After initialization, Lua can call:

```lua
print_message("hello")
local value = random_int(10)
```

`@LuaFunction(name = "...")` or `@LuaFunction("...")` overrides the Lua-visible function name. Without it, the Kotlin function name is used.

## Exporting Classes

Use a class provider when Lua should receive Kotlin objects with methods.

```kotlin
@LuaProvider("GameBindings")
class Player : ILuaStaticDecl by GameBindings.Player_LuaGenerated {
    @LuaProperty
    var health: Int = 100

    @LuaFunction
    fun damage(amount: Int) {
        health -= amount
    }
}

@LuaProvider("GameBindings")
object PlayerFactory {
    @LuaFunction
    fun create_player() = Player()
}
```

Lua usage:

```lua
local player = create_player()
player:damage(20)
print(player:get_health())
player:set_health(50)
```

Important call syntax:

- Use `object:method(args)` for instance methods.
- `object.method(args)` does not pass `self`, so generated instance bindings will receive the wrong arguments.

Abstract provider classes do not need to delegate to a generated `ILuaStaticDecl` object unless instances are pushed to Lua.

## Provider Options

`@LuaProvider` has these parameters:

```kotlin
annotation class LuaProvider(
    val className: String = "!",
    val inherit: String = "",
    val library: String = "_G"
)
```

- `className`: generated object name. `!` means use the processor default, normally `LuaGenerated`.
- `inherit`: reserved for inheritance metadata. The current active generator handles inherited bindings through provider classes processed in the same generated binding group.
- `library`: default Lua table for static bindings from this provider.

Example library export:

```kotlin
@LuaProvider(className = "GameBindings", library = "game")
object GameApi {
    @LuaFunction
    fun version() = "1.0"
}
```

Lua usage:

```lua
print(game.version())
```

`@LuaFunction` and `@LuaProperty` also accept a `library` parameter for overriding the provider default on individual global bindings.

## Properties

`@LuaProperty` exports property accessors:

```kotlin
@LuaProperty
var score: Int = 0
```

Generated Lua functions:

```lua
object:get_score()
object:set_score(10)
```

For immutable `val` properties, only the getter is generated. Setter export can be disabled:

```kotlin
@LuaProperty(exportPropertySetter = false)
var internalState: Int = 0
```

Property and function documentation export can be disabled with `exportDoc = false`.

## Initializing the Engine

Create a `LuaEngine` and initialize the generated provider:

```kotlin
class GameEngine(lua: party.iroiro.luajava.Lua) : LuaEngine(lua, { error ->
    println(error)
}) {
    init {
        GameBindings.initEngine(this)
    }
}
```

Then schedule Lua code:

```kotlin
val engine = GameEngine(party.iroiro.luajava.luajit.LuaJit())

engine.createTask(
    """
    local player = create_player()
    player:damage(25)
    print(player:get_health())
    """.trimIndent(),
    "example"
)

engine.loop()
engine.dispose()
```

The generated `initEngine` method registers static functions, instance dispatchers, metatables, and generated Lua dispatcher code.

## Task Execution

`LuaEngine.createTask(code, name, repeat = false)` wraps Lua source as a coroutine-backed task.

Each `engine.loop()` call advances runnable tasks. Tasks may finish, yield, fail to load, or fail at runtime.

Task statuses:

- `UNINITIALIZED`: task object exists but has not been initialized.
- `INITIALIZED`: task is loaded but not yet executed.
- `LOAD_ERROR`: Lua source failed to load.
- `RUNNING`: task is currently executing.
- `YIELDED`: task yielded and can be resumed on a later loop.
- `RUNTIME_ERROR`: Lua execution threw an error.
- `FINISHED`: task completed.

Finished non-repeating tasks are removed from `engine.tasks`. `removeAllFinished()` is also available for cleanup.

The engine installs task helpers into Lua:

```lua
task_yield()
task_yield(function() return condition end)
task_sleep(milliseconds)
get_current_task()
```

`task_yield(fn)` yields until `fn()` returns true. `task_sleep(time)` yields until the requested time has elapsed.

## Argument and Return Type Mapping

Supported argument mappings include:

- Kotlin numeric types: Lua number.
- `String`: Lua string.
- `Boolean`: Lua boolean.
- `List`: Lua table converted with LuaJava support.
- `LuaFunc`: Lua callback function captured as a Kotlin callable wrapper.
- `Lua.LuaValue`: raw Lua value.
- `ILuaStaticDecl`: Java object with generated metatable handling.
- Other objects: Java object conversion.

`@RetrieveEngine` can inject the current engine without consuming a Lua argument:

```kotlin
@LuaFunction
fun useEngine(@RetrieveEngine engine: LuaEngine) {
    engine.lua.push(5)
    engine.lua.setGlobal("value")
}
```

Supported return handling includes:

- `Unit`: no Lua return values.
- Numbers, strings, booleans: pushed directly.
- `ILuaStaticDecl`: pushed as Java object with generated metatable.
- `LuaMultiReturn`: pushes multiple return values.
- Other values: delegated to `LuaMain.pushNoInline`.

Multiple return example:

```kotlin
@LuaFunction
fun stats() = LuaMultiReturn(10, "ready", true)
```

Lua usage:

```lua
local count, status, ok = stats()
```

## Lua Callbacks

Lua functions passed into Kotlin can be received as `LuaFunc`:

```kotlin
@LuaFunction
fun hook(callback: LuaFunc) {
    callback.call(1, 2, 3)
}
```

Lua usage:

```lua
hook(function(a, b, c)
    print(a + b + c)
end)
```

`LuaFunc.call(...)` invokes the callback without keeping return values.

`LuaFunc.callAsTask(...)` starts the callback as a Lua task and returns the created `LuaTask`.

`LuaFunc.await(...)` can be used from Kotlin coroutine integration to yield until a callback task finishes.

## Kotlin Coroutine Integration

Classes can implement `CoroutineProvider` to expose coroutine-like Kotlin workflows to Lua.

```kotlin
@LuaProvider("GameBindings")
class Worker : ILuaStaticDecl by GameBindings.Worker_LuaGenerated, CoroutineProvider {
    @LuaFunction("delayed_value")
    fun delayedValue() = coroutine {
        val start = System.currentTimeMillis()
        yieldUntil { System.currentTimeMillis() - start > 1000 }
        breakTask(42)
    }
}
```

Lua usage:

```lua
local value = worker:delayed_value()
```

Generated Lua dispatcher code repeatedly calls `next_iter()`, yields while waiting, and returns the value supplied by `breakTask`.

Use `breakTask(value)` to complete with one value, `breakTask(v1, v2, ...)` for multiple values, and `yieldUntil { ... }` to suspend Lua task execution until a condition is true.

## Inheritance

Provider classes can inherit exported Lua methods and properties from parent provider classes.

```kotlin
@LuaProvider("GameBindings")
open class Entity : ILuaStaticDecl by GameBindings.Entity_LuaGenerated {
    @LuaFunction
    fun id() = "entity"
}

@LuaProvider("GameBindings")
class Player : Entity(), ILuaStaticDecl by GameBindings.Player_LuaGenerated {
    @LuaFunction
    fun name() = "player"
}
```

Lua usage:

```lua
local player = create_player()
print(player:id())
print(player:name())
```

The generated metatable for the child class chains to the parent `__index` table, so inherited Lua bindings remain available. Parent classes do not gain child methods.

Current inheritance support is intended for parent and child classes processed into the same generated binding group. Keep related provider classes under the same `className` unless the generator is extended for cross-provider metatable lookup.

## Generated Output

For each provider group, KSP generates an object named by `@LuaProvider(className = ...)` or the default processor option.

That object contains:

- `initEngine(engine)`: registers generated bindings into a `LuaEngine`.
- One metatable reference per exported class.
- One `<ClassName>_LuaGenerated` object per exported class implementing `ILuaStaticDecl`.

When the KSP option `export_doc=true` is set, the processor writes generated Markdown signatures from exported declarations. Declarations with `exportDoc = false` are omitted from that generated doc output.

## Runtime Lifecycle

Use `dispose()` when an engine is no longer needed.

`dispose()`:

- Marks the engine as disposed.
- Closes active task coroutines.
- Clears task state.
- Runs Lua GC.
- Releases tracked Lua references.
- Closes the Lua state.

Calling `loop()` after disposal throws `IllegalStateException`.

## Error Handling

`LuaEngine` accepts an error handler:

```kotlin
LuaEngine(lua) { message ->
    println(message)
}
```

Load and runtime errors are reported through this handler. Load failures set task status to `LOAD_ERROR`; runtime failures set it to `RUNTIME_ERROR` and remove the task from the engine.

Generated overload dispatchers choose a candidate by Lua argument count. If no candidate matches, Lua raises `No matching argument`.

## Current Limitations

- The project is WIP and may break API compatibility.
- Lua native binaries must be supplied separately by consumers.
- Vararg argument processing is declared but not implemented.
- Generated overload selection currently scores by required argument count, not full runtime type checking.
- Advanced multithreaded use is marked unstable in the runtime comments.
- Some generated code paths rely on LuaJava metatable behavior and may be sensitive to LuaJava implementation changes.

## Development Commands

Run tests:

```bash
./gradlew test
```

Build:

```bash
./gradlew build
```

The test sources under `src/test/kotlin/me/ddayo/aris/test` provide runnable examples covering static functions, instance methods, properties, inheritance, callbacks, coroutine integration, task lifecycle, and reference cleanup.
