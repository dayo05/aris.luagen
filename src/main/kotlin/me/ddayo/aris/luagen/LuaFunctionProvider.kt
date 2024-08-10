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
import party.iroiro.luajava.value.LuaValue


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

        val isMultiReturn = (if(isCoroutine) returnResolved?.arguments?.get(0)?.type?.resolve()?.declaration?.qualifiedName?.asString() else returnName) == LuaMultiReturn::class.qualifiedName

        val ptResolved = funcCall.parameters.map { it.type.resolve() }
        val minimumRequiredParameters =
            ptResolved.indexOfLast { !it.isMarkedNullable } + 1

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
            if(minimumRequiredParameters != 0)
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
        return ${if(isMultiReturn) "resolve_mrt(it:value())" else "it:value()"}
    end
    cur_task:yield(function() return it:finished() end)
end
""".trimIndent()
                )
            else appendLine(
                """
    return ${if(isMultiReturn) "resolve_mrt($fnName(...))" else "$fnName(...)"}
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
                    resolver.getClassDeclarationByName<LuaValue>()!!.asStarProjectedType(),
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
                environment.codeGenerator.createNewFile(
                    dependencies = Dependencies(true, *(files).toTypedArray()),
                    packageName = "me.ddayo.aris.gen",
                    fileName = "LuaGenerated"
                ).writer()
                    .apply {
                        write(
                            """
package me.ddayo.aris.gen

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
                        write(functions.values.joinToString("\n") { fn -> fn.ktBind.toString() })
                        write(
                            """
    }
}
"""
                        )

                        close()
                    }
                environment.codeGenerator.createNewFileByPath(Dependencies(false), "main_gen", "lua")
                    .writer().apply {
                        write(functions.values.joinToString("\n") { fn -> fn.luaBind.toString() })
                        close()
                    }
            }
        }
    }
}
