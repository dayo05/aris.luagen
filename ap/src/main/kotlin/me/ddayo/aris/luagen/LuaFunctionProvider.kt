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

    private data class BindTargetKt(
        val funcCall: KSFunctionDeclaration,
        val declaredClass: KSClassDeclaration?,
        val isPrivate: Boolean
    ) {
        private val returnResolved = funcCall.returnType?.resolve()
        private val returnName = returnResolved?.declaration?.qualifiedName?.asString()
        val isCoroutine = returnName == "me.ddayo.aris.CoroutineProvider.LuaCoroutineIntegration"

        val isMultiReturn =
            (if (isCoroutine) returnResolved?.arguments?.get(0)?.type?.resolve()?.declaration?.qualifiedName?.asString() else returnName) == "me.ddayo.aris.LuaMultiReturn"

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
            if (declaredClass != null || minimumRequiredParameters != 0)
                appendLine("val arg = (0 until lua.top).map { lua.get() }.reversed()")

            if (parResolved.unitResolved.isAssignableFrom(funcCall.returnType!!.resolve())) {
                if(declaredClass == null)
                    appendLine("${funcCall.qualifiedName!!.asString()}($invStr)")
                        .appendLine("0")
                else {
                    appendLine("lua.push(arg[0])")
                    appendLine("lua.getMetatable(-1)")
                    appendLine("lua.getGlobal(\"aris__obj_mt\")")
                    appendLine("lua.setMetatable(-3)")

                    appendLine("val rt = listOf((arg[0].toJavaObject() as ${intoProjectedStr(declaredClass)}).${funcCall.simpleName.asString()}($invStr))")
                    appendLine("lua.setMetatable(-2)")
                    appendLine("0")
                }
            } else {
                if (declaredClass == null)
                    appendLine("val rt = listOf(${funcCall.qualifiedName!!.asString()}($invStr))")
                else {
                    appendLine("lua.push(arg[0])")
                    appendLine("lua.getMetatable(-1)")
                    appendLine("lua.getGlobal(\"aris__obj_mt\")")
                    appendLine("lua.setMetatable(-3)")

                    appendLine("val rt = listOf((arg[0].toJavaObject() as ${intoProjectedStr(declaredClass)}).${funcCall.simpleName.asString()}($invStr))")
                    appendLine("lua.setMetatable(-2)")
                }
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
while true do
    local it = coroutine:next_iter()
    if it:is_break() then
        return ${if (isMultiReturn) "resolve_mrt(it:value())" else "it:value()"}
    end
    task_yield(function() return it:finished() end)
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

    private data class BindTargetLua(
        val name: String,
        val targets: MutableList<BindTargetKt>,
        val declaredClass: KSClassDeclaration?
    ) {
        val ktBind by lazy {
            StringBuilder().apply {
                targets.forEachIndexed { index, bindTargetKt ->
                    append(bindTargetKt.ktCallString)
                    if (declaredClass == null)
                        appendLine("lua.setGlobal(\"${name}_kt${index}\")")
                    else appendLine("lua.setField(-2, \"${name}_kt${index}\")")
                }
            }
        }

        val luaBind by lazy {
            StringBuilder().apply {
                if (declaredClass == null)
                    appendLine("function $name(...)")
                else appendLine(
                    "function aris_${
                        declaredClass.qualifiedName?.asString()?.replace('.', '_')
                    }_$name(...)"
                )
                append(
                    """    local as_table = { ... }
    local table_size = #as_table
    local score = -1
    local sel_fn = function() error("No matching argument") end
"""
                )
                targets.forEachIndexed { index, bindTargetKt ->
                    append(
                        bindTargetKt.scoreCalcLua(
                            if (declaredClass == null)
                                "${name}_kt${index}"
                            else "aris_${declaredClass.qualifiedName?.asString()?.replace('.', '_')}.${name}_kt${index}"
                        )
                    )
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
                    if (it.isPrivate) return@forEach
                    if (declaredClass == null)
                        appendLine("## $name(${it.signature})")
                    else appendLine("## ${declaredClass.simpleName.asString()}:$name(${it.signature})")
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

        fun intoProjectedStr(classDecl: KSClassDeclaration): String {
            val s = StringBuilder(classDecl.qualifiedName!!.asString())
            if (classDecl.typeParameters.isNotEmpty()) {
                s.append('<')
                for (x in 1 until classDecl.typeParameters.size)
                    s.append("*, ")
                s.append("*>")
            }
            return s.toString()
        }

    }

    @OptIn(KspExperimental::class)
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        logger = environment.logger
        return object : SymbolProcessor {
            val files = mutableSetOf<KSFile>()
            val functions = mutableMapOf<String, MutableMap<String, MutableMap<String, BindTargetLua>>>()

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
                    resolver.getClassDeclarationByName("me.ddayo.aris.CoroutineProvider.LuaCoroutineIntegration")!!
                        .asStarProjectedType(),
                )
                resolver.getSymbolsWithAnnotation(luaProviderAnnotationName).let { providers ->
                    val ret = providers.filter { !it.validate() }
                    environment.logger.warn(ret.joinToString { it.location.toString() })
                    environment.logger.warn(ret.count { it is KSClassDeclaration }.toString())

                    val defCln = environment.options["default_class_name"] ?: "LuaGenerated"
                    providers.filter { it is KSClassDeclaration }
                        .forEach { classDecl ->
                            val cln = classDecl.getAnnotationsByType(LuaProvider::class).firstOrNull()?.className.let {
                                if(it == null || it == "!") defCln
                                else it
                            }
                            classDecl.accept(object : KSVisitorVoid() {
                                override fun visitClassDeclaration(
                                    classDeclaration: KSClassDeclaration,
                                    data: Unit
                                ) {
                                    when (classDeclaration.classKind) {
                                        ClassKind.OBJECT -> {
                                            classDeclaration.getDeclaredFunctions().mapNotNull {
                                                it.getAnnotationsByType(
                                                    LuaFunction::class
                                                ).firstOrNull()?.let { annot -> it to annot }
                                            }
                                                .forEach { (fn, annot) ->
                                                    val fnName =
                                                        if (annot.name == "!") fn.simpleName.asString() else annot.name
                                                    val overloadFns =
                                                        functions.getOrPut(cln) { mutableMapOf() }
                                                            .getOrPut("null") { mutableMapOf() }.getOrPut(fnName) {
                                                            BindTargetLua(
                                                                fnName,
                                                                mutableListOf(),
                                                                null
                                                            )
                                                        }
                                                    overloadFns.targets.add(BindTargetKt(fn, null, !annot.exportDoc))
                                                    files.add(classDecl.containingFile!!)
                                                }
                                        }

                                        ClassKind.CLASS -> {
                                            classDeclaration.getDeclaredFunctions().mapNotNull {
                                                it.getAnnotationsByType(
                                                    LuaFunction::class
                                                ).firstOrNull()?.let { annot -> it to annot }
                                            }.forEach { (fn, annot) ->
                                                val fnName =
                                                    if (annot.name == "!") fn.simpleName.asString() else annot.name
                                                val overloadFns =
                                                    functions.getOrPut(cln) { mutableMapOf() }.getOrPut(intoProjectedStr(classDeclaration)) { mutableMapOf() }
                                                        .getOrPut(fnName) {
                                                            BindTargetLua(
                                                                fnName,
                                                                mutableListOf(),
                                                                classDeclaration
                                                            )
                                                        }
                                                overloadFns.targets.add(
                                                    BindTargetKt(
                                                        fn,
                                                        classDeclaration,
                                                        !annot.exportDoc
                                                    )
                                                )
                                                files.add(classDecl.containingFile!!)
                                            }
                                        }

                                        else -> throw LuaBindingException("Cannot process ${classDeclaration.qualifiedName}: Provider not supports object")
                                    }
                                }
                            }, Unit)
                        }
                    return ret.toList()
                }
            }

            override fun finish() {
                super.finish()

                val pkg = environment.options["package_name"] ?: "me.ddayo.aris.gen"

                functions.entries.forEach { (clName, cls) ->
                    val luaCode =
                        cls.values.joinToString("\n") { fn -> fn.values.joinToString("\n") { it.luaBind.toString() } }

                    val ktCode = StringBuilder().apply {
                        appendLine(
                            """package $pkg

import me.ddayo.aris.LuaMultiReturn
import party.iroiro.luajava.Lua
import party.iroiro.luajava.LuaException
import me.ddayo.aris.LuaMain.push

object $clName {
    fun initLua(lua: Lua) {
"""
                        )
                        appendLine(cls.entries.joinToString("\n") { fn ->
                            val sb = StringBuilder()
                            if (fn.key != "null") {
                                sb.appendLine("lua.newTable()")
                            }

                            sb.append(fn.value.values.joinToString("\n") { it.ktBind.toString() })

                            if (fn.key != "null") {
                                sb.appendLine(
                                    "lua.setGlobal(\"aris_${
                                        fn.key.replace('.', '_').replace("<", "").replace(">", "").replace(",", "")
                                            .replace(" ", "").replace("*", "")
                                    }\")"
                                )
                            }
                            sb.toString()
                        }) // write all static functions
                        appendLine("        lua.load(\"\"\"$luaCode\"\"\")")
                        appendLine("lua.pCall(0, 0)")
                        appendLine(cls.entries.joinToString("") { fn ->
                            if (fn.key == "null") return@joinToString ""
                            val cln = fn.key.replace('.', '_').replace("<", "").replace(">", "").replace(",", "")
                                .replace(" ", "").replace("*", "")
                            val sb = StringBuilder()
                            sb.appendLine(
                                """
                                lua.newTable()
                                lua.getGlobal("aris__gc")
                                lua.setField(-2, "__gc")
                                lua.getGlobal("aris__newindex")
                                lua.setField(-2, "__newindex")
                                lua.getGlobal("aris__eq")
                                lua.setField(-2, "__eq")
                                lua.newTable()"""
                            )
                            fn.value.forEach {
                                sb.appendLine(
                                    """
                                    lua.getGlobal("aris_${cln}_${it.key}")
                                    lua.setField(-2, "${it.key}")
                                    """
                                )
                            }
                            sb.appendLine(
                                """
                                lua.setField(-2, "__index")
                                lua.setGlobal("aris_${cln}_mt")
                                    
                                """
                            )
                            sb.toString()
                        })
                        appendLine("""
    }
}
""" + cls.entries.joinToString("\n") { (k, v) ->
                            if (k == "null") return@joinToString ""
                            StringBuilder().apply {
                                appendLine("object ${v.values.first().declaredClass?.simpleName?.asString()}_LuaGenerated {")
                                appendLine("    fun $k.pushLua(lua: Lua) {")
                                appendLine("        lua.pushJavaObject(this)")
                                appendLine(
                                    "        lua.getGlobal(\"aris_${
                                        v.values.first().declaredClass?.qualifiedName?.asString()?.replace(".", "_")
                                    }_mt\")"
                                )
                                appendLine("        lua.setMetatable(-2)")
                                appendLine("    }")
                                appendLine("}")
                            }.toString()
                        }
                        )
                    }.toString()

                    environment.codeGenerator.createNewFile(
                        dependencies = Dependencies(true, *(files).toTypedArray()),
                        packageName = pkg,
                        fileName = clName
                    ).writer()
                        .apply {
                            write(ktCode)
                            close()
                        }
                    if (environment.options["export_lua"] == "true")
                        environment.codeGenerator.createNewFileByPath(Dependencies(false), clName, "lua")
                            .writer().apply {
                                write(luaCode)
                                close()
                            }
                    if (environment.options["export_doc"] == "true")
                        environment.codeGenerator.createNewFileByPath(Dependencies(false), "${clName}_doc", "md")
                            .writer().apply {
                                write(cls.values.joinToString("\n\n") {
                                    it.values.map { it.docString }.filter { it.isNotBlank() }.joinToString("\n\n")
                                })
                                close()
                            }
                }
            }
        }
    }
}
