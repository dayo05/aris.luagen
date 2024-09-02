@file:OptIn(KspExperimental::class)

package me.ddayo.aris.luagen

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.containingFile
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import me.ddayo.aris.CoroutineProvider


class LuaBindingException(message: String) : Exception(message)

class LuaFunctionProcessorProvider : SymbolProcessorProvider {
    internal data class ParResolved(
        val numberResolved: KSType,
        val longResolved: KSType,
        val intResolved: KSType,
        val shortResolved: KSType,
        val byteResolved: KSType,
        val charResolved: KSType,
        val doubleResolved: KSType,
        val floatResolved: KSType,
        val stringResolved: KSType,
        val booleanResolved: KSType,
        val mapResolved: KSType,
        val classResolved: KSType,
        val unitResolved: KSType,
        val luaValueResolved: KSType,
        val coroutineResolved: KSType
    )

    private data class BindTargetKt(val funcCall: KSFunctionDeclaration) {
        private val returnResolved = funcCall.returnType?.resolve()
        private val returnName = returnResolved?.declaration?.qualifiedName?.asString()
        val isCoroutine = returnName == CoroutineProvider.LuaCoroutineIntegration::class.qualifiedName

        val isMultiReturn =
            (if (isCoroutine) returnResolved?.arguments?.get(0)?.type?.resolve()?.declaration?.qualifiedName?.asString() else returnName) == LuaMultiReturn::class.qualifiedName

        val ptResolved = funcCall.parameters.map { it.type.resolve() }
        val minimumRequiredParameters =
            ptResolved.indexOfLast { !it.isMarkedNullable } + 1

        val signature =
            funcCall.parameters.joinToString(", ") { "${it.name?.asString()}: ${it.type.resolve().declaration.simpleName.asString()}" }
        val doc = funcCall.docString

        val invStr = ptResolved.mapIndexed { ix, it ->
            StringBuilder()
                .append("arg[")
                .append(ix)
                .append(']').apply {
                    if (parResolved.stringResolved.isAssignableFrom(it))
                        append(".toString()")
                    else if (parResolved.numberResolved.isAssignableFrom(it))
                        append(
                            when {
                                parResolved.longResolved.isAssignableFrom(it) -> ".toInteger()"
                                parResolved.intResolved.isAssignableFrom(it) -> ".toInteger().toInt()"
                                parResolved.shortResolved.isAssignableFrom(it) -> ".toInteger().toShort()"
                                parResolved.byteResolved.isAssignableFrom(it) -> ".toInteger().toByte()"
                                parResolved.doubleResolved.isAssignableFrom(it) -> ".toNumber()"
                                parResolved.floatResolved.isAssignableFrom(it) -> ".toNumber().toFloat()"
                                else -> throw Exception("Not supported type")
                            }
                        )
                    else if (parResolved.booleanResolved.isAssignableFrom(it))
                        append(".toJavaObject() as Boolean")
                    else if (!parResolved.luaValueResolved.isAssignableFrom(it))
                        append(".toJavaObject()")

                    toString()
                }
        }
            .joinToString(", ")

        val ktCallString = StringBuilder().apply {
            appendLine("lua.push { lua ->")
            if (minimumRequiredParameters != 0)
                appendLine("val arg = (0 until lua.top).map { lua.get() }.reversed()")
            if (parResolved.unitResolved.isAssignableFrom(funcCall.returnType!!.resolve())) {
                appendLine("${funcCall.qualifiedName!!.asString()}($invStr)")
                    .appendLine("0")
            } else {
                appendLine("val rt = listOf(${funcCall.qualifiedName!!.asString()}($invStr))")
                appendLine("rt.forEach { push(lua, it) }")
                appendLine("rt.size")
            }
            appendLine("}")
        }

        fun scoreCalcLua(fnName: String) = StringBuilder().apply {
            appendLine(
                """
if table_size >= $minimumRequiredParameters then
    local task_score = 0
    if task_score >= score then
        score = task_score
        sel_fn = function(...)
""".trimMargin()
            )

            if (isCoroutine)
                appendLine(
                    """local coroutine = $fnName(...) -- get LuaCoroutine instance
local cur_task = get_current_task()
while true do
    local it = coroutine:next_iter()
    if it:is_break() then
        return ${if (isMultiReturn) "resolve_mrt(it:value())" else "it:value()"}
    end
    cur_task:yield(function() return it:finished() end)
end
""".trimIndent()
                )
            else appendLine(
                """
    return ${if (isMultiReturn) "resolve_mrt($fnName(...))" else "$fnName(...)"}
""".trimIndent()
            )

            appendLine("        end")
            appendLine("    end")
            appendLine("end")
        }
    }

    private data class BindTargetLua(val name: String, val targets: MutableList<BindTargetKt>) {
        val ktBind by lazy {
            StringBuilder().apply {
                targets.forEachIndexed { index, bindTargetKt ->
                    append(bindTargetKt.ktCallString)
                    appendLine("lua.setGlobal(\"${name}_kt${index}\")")
                }
            }
        }

        val luaBind by lazy {
            StringBuilder().apply {
                append(
                    """
function ${name}(...)
    local as_table = { ... }
    local table_size = #as_table
    local score = -1
    local sel_fn = function() error("No matching argument") end
"""
                )
                targets.forEachIndexed { index, bindTargetKt ->
                    append(bindTargetKt.scoreCalcLua("${name}_kt${index}"))
                }
                append(
                    """
    return sel_fn(...)
end
"""
                )
            }
        }

        val docString by lazy {
            StringBuilder().apply {
                targets.forEach {
                    appendLine("## $name(${it.signature})")
                    it.doc?.let { doc ->
                        appendLine("```")
                        append(' ')
                        appendLine(doc.trim())
                        appendLine("```")
                    }
                }
            }
        }
    }

    companion object {
        private val luaFunctionAnnotationName = LuaFunction::class.java.canonicalName
        private val luaProviderAnnotationName = LuaProvider::class.java.canonicalName
        internal lateinit var parResolved: ParResolved private set
        internal lateinit var logger: KSPLogger private set
    }

    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        logger = environment.logger
        return object : SymbolProcessor {
            val files = mutableSetOf<KSFile>()
            val functions = mutableMapOf<String, BindTargetLua>()

            override fun process(resolver: Resolver): List<KSAnnotated> {
                parResolved = ParResolved(
                    resolver.getClassDeclarationByName<Number>()!!.asStarProjectedType(),
                    resolver.getClassDeclarationByName<Long>()!!.asStarProjectedType(),
                    resolver.getClassDeclarationByName<Int>()!!.asStarProjectedType(),
                    resolver.getClassDeclarationByName<Short>()!!.asStarProjectedType(),
                    resolver.getClassDeclarationByName<Byte>()!!.asStarProjectedType(),
                    resolver.getClassDeclarationByName<Char>()!!.asStarProjectedType(),
                    resolver.getClassDeclarationByName<Double>()!!.asStarProjectedType(),
                    resolver.getClassDeclarationByName<Float>()!!.asStarProjectedType(),
                    resolver.getClassDeclarationByName<String>()!!.asStarProjectedType(),
                    resolver.getClassDeclarationByName<Boolean>()!!.asStarProjectedType(),
                    resolver.getClassDeclarationByName<Map<Any, Any>>()!!.asStarProjectedType(),
                    resolver.getClassDeclarationByName<Class<*>>()!!.asStarProjectedType(),
                    resolver.getClassDeclarationByName<Unit>()!!.asStarProjectedType(),
                    resolver.getClassDeclarationByName("party.iroiro.luajava.value.LuaValue")!!.asStarProjectedType(),
                    resolver.getClassDeclarationByName<CoroutineProvider.LuaCoroutineIntegration<*>>()!!
                        .asStarProjectedType(),
                )
                resolver.getSymbolsWithAnnotation(luaProviderAnnotationName).let { providers ->
                    val ret = providers.filter { !it.validate() }

                    providers.filter { it is KSClassDeclaration && it.validate() }
                        .forEach { classDecl ->
                            classDecl.accept(object : KSVisitorVoid() {
                                override fun visitClassDeclaration(
                                    classDeclaration: KSClassDeclaration,
                                    data: Unit
                                ) {
                                    if (classDeclaration.classKind != ClassKind.OBJECT)
                                        throw LuaBindingException("Cannot process ${classDeclaration.qualifiedName}: Provider must be singletone object")

                                    classDeclaration.getDeclaredFunctions().mapNotNull {
                                        it.getAnnotationsByType(
                                            LuaFunction::class
                                        ).firstOrNull()?.let { annot -> it to annot }
                                    }
                                        .forEach { (fn, annot) ->
                                            val fnName = if (annot.name == "!") fn.simpleName.asString() else annot.name
                                            val overloadFns =
                                                functions.getOrPut(fnName) { BindTargetLua(fnName, mutableListOf()) }
                                            overloadFns.targets.add(BindTargetKt(fn))
                                            files.add(classDecl.containingFile!!)
                                        }
                                }
                            }, Unit)
                        }
                    return ret.toList()
                }
            }

            override fun finish() {
                super.finish()

                val luaCode = """local _scheduler = {}
_scheduler.__index = _scheduler

local function true_fn() return true end

-- register new coroutine
function _scheduler:new(fn, opt)
    local o = {}
    o.opt = opt
    o.execute = true_fn
    setmetatable(o, self)
    o.task = coroutine.create(fn)
    return o
end

function _scheduler:resume(...)
    self.execute = nil
    return coroutine.resume(self.task, self, ...)
end

function _scheduler:sleep(time)
    local current_time = get_time()
    self.execute = function() return get_time() - current_time > time end
    coroutine.yield(self)
end

function _scheduler:yield(fn) -- the most of use case of parameter `fn` is for coroutine integration
    self.execute = fn or true_fn
    coroutine.yield(self)
end

Scheduler = _scheduler -- move it to global

local registered_tasks = {}
function register_task(script, name)
    if(name == nil) then name = "lua" end
    local fn = load("return function(task)\n" .. script .. "\nend", name, "t", _G)()
    registered_tasks[#registered_tasks+1] = Scheduler:new(fn, { name = name })
end

local current_task
function get_current_task() return current_task end

function loop()
    for i, v in ipairs(registered_tasks) do
        if v:execute() then
            current_task = v
            local t, s = v:resume()
            if not t then debug_log(nil, "error inside coroutine " .. v.opt.name .. ": " .. s) end
            if coroutine.status(v.task) == "dead" then registered_tasks[i] = nil end
        end
    end
end

""".trimIndent() + functions.values.joinToString("\n") { fn -> fn.luaBind.toString() }

                val ktCode = StringBuilder().apply {
                    appendLine(
                        """package me.ddayo.aris.gen

import me.ddayo.aris.luagen.LuaMultiReturn
import party.iroiro.luajava.Lua
import party.iroiro.luajava.LuaException
import party.iroiro.luajava.luajit.LuaJit

object LuaGenerated {
    fun <T> push(lua: Lua, it: T) {
        when(it) {
            null -> lua.pushNil()
            is Number -> lua.push(it)
            is Boolean -> lua.push(it)
            is String -> lua.push(it)
            is Map<*, *> -> lua.push(it)
            is Class<*> -> lua.pushJavaClass(it)
            else -> lua.pushJavaObject(it as Any)
        }
    }
    
    fun initLua(lua: LuaJit) {
        lua.push { lua ->
            val r = lua.get().toJavaObject() as? LuaMultiReturn
            r?.luaFn(lua) ?: 0
        }
        lua.setGlobal("resolve_mrt")
"""
                    )
                    appendLine(functions.values.joinToString("\n") { fn -> fn.ktBind.toString() })
                    appendLine("        lua.load(\"\"\"$luaCode\"\"\")")
                    appendLine(
                        """lua.pCall(0, 0)
    }
}
"""
                    )
                }.toString()

                environment.codeGenerator.createNewFile(
                    dependencies = Dependencies(true, *(files).toTypedArray()),
                    packageName = "me.ddayo.aris.gen",
                    fileName = "LuaGenerated"
                ).writer()
                    .apply {
                        write(ktCode)
                        close()
                    }
                if (environment.options["export_lua"] == "true")
                    environment.codeGenerator.createNewFileByPath(Dependencies(false), "main_gen", "lua")
                        .writer().apply {
                            write(luaCode)
                            close()
                        }
                if (environment.options["export_doc"] == "true")
                    environment.codeGenerator.createNewFileByPath(Dependencies(false), "lua_doc", "md")
                        .writer().apply {
                            write(functions.values.joinToString("\n\n") { it.docString })
                            close()
                        }
            }
        }
    }
}
