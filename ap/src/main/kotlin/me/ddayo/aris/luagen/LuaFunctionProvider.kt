@file:OptIn(KspExperimental::class)

package me.ddayo.aris.luagen

import com.google.devtools.ksp.*
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*


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
        val coroutineResolved: KSType,
        val staticDeclResolved: KSType
    )

    private data class BindTargetKt(
        val funcCall: KSFunctionDeclaration,
        val declaredClass: KSClassDeclaration?,
        val isPrivate: Boolean
    ) {
        private val returnResolved = funcCall.returnType?.resolve()
        private val returnName = returnResolved?.declaration?.qualifiedName?.asString()
        val isCoroutine = returnName == "me.ddayo.aris.CoroutineProvider.LuaCoroutineIntegration"

        val ptResolved = funcCall.parameters.map { it.type.resolve() }
        val minimumRequiredParameters =
            ptResolved.indexOfLast { !it.isMarkedNullable } + 1

        val signature =
            funcCall.parameters.joinToString(", ") { "${it.name?.asString()}: ${it.type.resolve().declaration.simpleName.asString()}" }
        val doc = funcCall.docString

        val invStr = ptResolved.mapIndexed { ix, it ->
            StringBuilder()
                .append("arg[")
                .append(if (declaredClass == null) ix else (ix + 1))
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
                    // else if (parResolved.booleanResolved.isAssignableFrom(it)) append(".toJavaObject() as Boolean")
                    else if (!parResolved.luaValueResolved.isAssignableFrom(it))
                        append(".toJavaObject() as ${it.declaration.qualifiedName?.asString()}")

                    toString()
                }
        }
            .joinToString(", ")

        val ktCallString = StringBuilder().apply {
            appendLine("lua.push { lua ->")

            var needResolve = 0
            if (declaredClass != null || minimumRequiredParameters != 0) {
                appendLine("val arg = (0 until lua.top).map { lua.get() }.reversed()")
                if (declaredClass != null || ptResolved.any { parResolved.staticDeclResolved.isAssignableFrom(it) }) {
                    appendLine("lua.getGlobal(\"aris__obj_mt\")")
                    val ll = mutableListOf<Int>()
                    ptResolved.forEachIndexed { index, ksType ->
                        if (parResolved.staticDeclResolved.isAssignableFrom(ksType)) ll.add(
                            if (declaredClass == null) index else (index + 1)
                        )
                    }
                    if (declaredClass != null && parResolved.staticDeclResolved.isAssignableFrom(
                            declaredClass.asStarProjectedType()
                        )
                    )
                        ll.add(0)
                    needResolve = ll.size

                    ll.forEachIndexed { index, v ->
                        appendLine("lua.push(arg[$v])")
                        appendLine("lua.getMetatable(-1)")
                        appendLine("lua.pushValue(-${index * 2 + 3})")
                        appendLine("lua.setMetatable(-3)")
                    }
                }
            }

            if (declaredClass == null)
                appendLine("val rt = ${funcCall.qualifiedName!!.asString()}($invStr)")
            else
                appendLine("val rt = (arg[0].toJavaObject() as ${intoProjectedStr(declaredClass)}).${funcCall.simpleName.asString()}($invStr)")
            if (needResolve != 0) {
                repeat(needResolve) {
                    appendLine("lua.setMetatable(-2)")
                    appendLine("lua.pop(1)")
                }
                appendLine("lua.pop(1)")
            }
            appendLine("return@push push(lua, rt)")
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
        return it:value()
    end
    task_yield(function() return it:finished() end)
end
""".trimIndent()
                )
            else appendLine(
                """
    return $fnName(...)
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
            val inherit = mutableMapOf<String, String>()

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
                    resolver.getClassDeclarationByName("me.ddayo.aris.ILuaStaticDecl")!!.asStarProjectedType(),
                )
                val defCln = environment.options["default_class_name"] ?: "LuaGenerated"

                val byProvider = mutableMapOf<String, MutableList<KSClassDeclaration>>()
                val ret: Sequence<KSAnnotated>
                resolver.getSymbolsWithAnnotation(luaProviderAnnotationName).let { providers ->
                    ret = providers.filter { !it.validate() }
                    environment.logger.warn("it.validate failed on " + ret.joinToString { it.location.toString() })

                    providers.mapNotNull { it as? KSClassDeclaration }
                        .forEach { classDeclaration ->
                            val cln =
                                classDeclaration.getAnnotationsByType(LuaProvider::class).firstOrNull()?.className.let {
                                    if (it == null || it == "!") defCln
                                    else it
                                }
                            byProvider.getOrPut(cln) { mutableListOf() }.add(classDeclaration)
                        }
                }

                byProvider.forEach { (provider, classes) ->
                    functions[provider] = mutableMapOf()
                    val fns = functions[provider]!!
                    val sorter = Sorter()
                    classes.forEach { classDeclaration ->
                        sorter.addInstance(classDeclaration.qualifiedName!!.asString(), sorter.SorterInstance {
                            environment.logger.warn("Processing ${classDeclaration.simpleName.asString()}")
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
                                                fns.getOrPut("null") { mutableMapOf() }.getOrPut(fnName) {
                                                    BindTargetLua(
                                                        fnName,
                                                        mutableListOf(),
                                                        null
                                                    )
                                                }
                                            overloadFns.targets.add(BindTargetKt(fn, null, !annot.exportDoc))
                                            files.add(classDeclaration.containingFile!!)
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
                                            fns.getOrPut(intoProjectedStr(classDeclaration)) { mutableMapOf() }
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
                                        files.add(classDeclaration.containingFile!!)
                                    }
                                }

                                else -> throw LuaBindingException("Cannot process ${classDeclaration.qualifiedName}: Provider not supports object")
                            }
                        })
                    }

                    classes.forEach { current ->
                        current.superTypes.map { it.resolve().declaration }.filterIsInstance<KSClassDeclaration>()
                            .forEach { parent ->
                                environment.logger.warn("${current.qualifiedName?.asString()} -> ${parent.qualifiedName?.asString()}")
                                if (parent.isAnnotationPresent(LuaProvider::class)) {
                                    inherit[current.qualifiedName!!.asString().replace(".", "_")] =
                                        parent.qualifiedName!!.asString()
                                    if (sorter[parent.qualifiedName!!.asString()] != null)
                                        sorter.setParent(
                                            parent.qualifiedName!!.asString(),
                                            current.qualifiedName!!.asString()
                                        )
                                }
                            }
                    }

                    sorter.process()
                }

                return ret.toList()
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
import me.ddayo.aris.ILuaStaticDecl

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

                            inherit[cln]?.let {
                                sb.appendLine(
                                    """
                                lua.newTable()
                                lua.getGlobal("aris_${it.replace(".", "_")}_mt")
                                lua.getField(-1, "__index")
                                lua.setField(-3, "__index")
                                lua.pop(1)
                                lua.setMetatable(-2)
                                """.trimIndent()
                                )
                            }

                            sb.appendLine("lua.setField(-2, \"__index\")")
                            sb.appendLine("lua.setGlobal(\"aris_${cln}_mt\")")
                            sb.toString()
                        })
                        appendLine("""
    }
}
""" + cls.entries.joinToString("\n") { (k, v) ->
                            if (k == "null") return@joinToString ""
                            StringBuilder().apply {
                                appendLine("object ${v.values.first().declaredClass?.simpleName?.asString()}_LuaGenerated: ILuaStaticDecl {")
                                appendLine("    override fun toLua(lua: Lua) {")
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
