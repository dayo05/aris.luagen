@file:OptIn(KspExperimental::class)

package me.ddayo.aris.luagen

import com.google.devtools.ksp.*
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*


class LuaBindingException(message: String) : Exception(message)

class LuaFunctionProcessorProvider : SymbolProcessorProvider {
    private class BindTargetKt(
        val funcCall: KSFunctionDeclaration, val declaredClass: KSClassDeclaration?, val isPrivate: Boolean
    ) {
        val isStatic = declaredClass == null
        val returnResolved = funcCall.returnType?.resolve()
        val returnName = returnResolved?.declaration?.qualifiedName?.asString()
        val isCoroutine = returnName == "me.ddayo.aris.CoroutineProvider.LuaCoroutineIntegration"

        val ptResolved: MutableList<Pair<KSValueParameter?, KSType>> =
            funcCall.parameters.map { it to it.type.resolve() }.toMutableList()

        init {
            declaredClass?.let { ptResolved.add(0, null to it.asStarProjectedType()) }
        }

        val signature =
            funcCall.parameters.joinToString(", ") { "${it.name?.asString()}: ${it.type.resolve().declaration.simpleName.asString()}" }
        val doc = funcCall.docString

        var argPtr = 0
        val ptStr = ptResolved.map {
            StringBuilder().apply {
                argPtr += ArgumentManager.argFilters.firstOrNull { filter -> filter.isValid(it.second, it.first) }!!
                    .resolve(argPtr, this, it.second.declaration as KSClassDeclaration, it.first)
            }
        }

        val ktCallString = StringBuilder().apply {

            val invStr = ptStr.let {
                if (!isStatic) it.drop(1)
                else it
            }.joinToString(", ")

            appendLine("lua.push { lua ->")

            var needResolve = 0

            // determine to unpack or not
            if (argPtr != 0) {
                appendLine("val arg = (0 until lua.top).map { lua.get() }.reversed()")

                // determine to replace metatable
                if (ptResolved.any { parResolved.staticDeclResolved.isAssignableFrom(it.second) }) {
                    appendLine("lua.getGlobal(\"aris__obj_mt\")")
                    val ll = mutableListOf<Int>()
                    ptResolved.forEachIndexed { index, ksType ->
                        if (parResolved.staticDeclResolved.isAssignableFrom(ksType.second)) ll.add(index)
                    }
                    needResolve = ll.size

                    ll.forEachIndexed { index, v ->
                        appendLine("lua.push(arg[$v])")
                        appendLine("lua.getMetatable(-1)")
                        appendLine("lua.pushValue(-${index * 2 + 3})")
                        appendLine("lua.setMetatable(-3)")
                    }
                }
            }

            if (isStatic) appendLine("val rt = ${funcCall.qualifiedName!!.asString()}($invStr)")
            else appendLine("val rt = (${ptStr[0]}).${funcCall.simpleName.asString()}($invStr)")

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
if table_size >= $argPtr then
    local task_score = 0
    if task_score >= score then
        score = task_score
        sel_fn = function(...)
""".trimMargin()
            )

            if (isCoroutine) appendLine(
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
            else appendLine("return $fnName(...)")

            appendLine("        end")
            appendLine("    end")
            appendLine("end")
        }
    }

    private data class BindTargetLua(
        val name: String, val targets: MutableList<BindTargetKt>, val declaredClass: KSClassDeclaration?
    ) {
        val ktBind by lazy {
            StringBuilder().apply {
                targets.forEachIndexed { index, bindTargetKt ->
                    append(bindTargetKt.ktCallString)
                    if (declaredClass == null) appendLine("lua.setGlobal(\"${name}_kt${index}\")")
                    else appendLine("lua.setField(-2, \"${name}_kt${index}\")")
                }
            }
        }

        val luaBind by lazy {
            StringBuilder().apply {
                if (declaredClass == null) appendLine("function $name(...)")
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
                            if (declaredClass == null) "${name}_kt${index}"
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
                    if (declaredClass == null) appendLine("## $name(${it.signature})")
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

    private abstract class AbstractKTProviderInstance(val inner: MutableMap<String, BindTargetLua> = mutableMapOf()) {
        val luaBindings
            get() = StringBuilder().apply {
                inner.values.joinTo(this, "\n") { it.luaBind }
            }

        open val ktBindings
            get() = StringBuilder().apply {
                inner.values.joinTo(this, "\n") { it.ktBind }
            }

        val docStrings
            get() = StringBuilder().apply {
                inner.values.map { it.docString }.filter { it.isNotBlank() }.joinTo(this, "\n\n")
            }

        abstract fun getFunction(name: String): BindTargetLua

        open val objectGenerated = StringBuilder()
        open val metatableGenerated = StringBuilder()
        var inherit: String? = null
    }

    private class KTObjectProviderInstance : AbstractKTProviderInstance() {
        override fun getFunction(name: String) = inner.getOrPut(name) {
            BindTargetLua(
                name, mutableListOf(), null
            )
        }
    }

    private class KTProviderInstance(val declaredClass: KSClassDeclaration) : AbstractKTProviderInstance() {
        override fun getFunction(name: String) = inner.getOrPut(name) {
            BindTargetLua(
                name, mutableListOf(), declaredClass
            )
        }

        val className = declaredClass.qualifiedName!!.asString()
        val simpleName = declaredClass.simpleName.asString()

        override val ktBindings: StringBuilder
            get() = StringBuilder().apply {
                appendLine("lua.newTable()")

                append(super.ktBindings)

                appendLine(
                    "lua.setGlobal(\"aris_${
                        className.replace('.', '_')
                    }\")"
                )
            }

        override val objectGenerated = StringBuilder().apply {
            appendLine("object ${simpleName}_LuaGenerated: ILuaStaticDecl {")
            appendLine("    override fun toLua(lua: Lua) {")
            appendLine(
                "        lua.getGlobal(\"aris_${
                    className.replace(".", "_")
                }_mt\")"
            )
            appendLine("        lua.setMetatable(-2)")
            appendLine("    }")
            appendLine("}")
        }

        override val metatableGenerated: StringBuilder
            get() = StringBuilder().apply {
                val cln = className.replace('.', '_')
                appendLine(
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

                inner.keys.forEach {
                    appendLine(
                        """
                                    lua.getGlobal("aris_${cln}_${it}")
                                    lua.setField(-2, "$it")
                                    """
                    )
                }

                inherit?.let {
                    appendLine(
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

                appendLine("lua.setField(-2, \"__index\")")
                appendLine("lua.setGlobal(\"aris_${cln}_mt\")")
            }
    }

    companion object {
        private val luaFunctionAnnotationName = LuaFunction::class.java.canonicalName
        private val luaProviderAnnotationName = LuaProvider::class.java.canonicalName
        internal lateinit var parResolved: ParameterCache private set
        internal lateinit var logger: KSPLogger private set
    }

    @OptIn(KspExperimental::class)
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        logger = environment.logger
        return object : SymbolProcessor {
            val files = mutableSetOf<KSFile>()

            val functions = mutableMapOf<String, MutableList<AbstractKTProviderInstance>>()

            override fun process(resolver: Resolver): List<KSAnnotated> {
                parResolved = ParameterCache.init(resolver)
                val defCln = environment.options["default_class_name"] ?: "LuaGenerated"

                val byProvider = mutableMapOf<String, MutableList<KSClassDeclaration>>()
                resolver.getSymbolsWithAnnotation(luaProviderAnnotationName).let { providers ->
                    providers.mapNotNull { it as? KSClassDeclaration }.forEach { classDeclaration ->
                        val cln =
                            classDeclaration.getAnnotationsByType(LuaProvider::class).firstOrNull()?.className.let {
                                if (it == null || it == "!") defCln
                                else it
                            }
                        byProvider.getOrPut(cln) { mutableListOf() }.add(classDeclaration)
                    }
                }

                byProvider.forEach { (provider, classes) ->
                    val fns = functions.getOrPut(provider) { mutableListOf() }
                    val sorter = Sorter()
                    val inherit = mutableMapOf<String, String>()

                    val nilFn = KTObjectProviderInstance().also { fns.add(it) }
                    classes.forEach { classDeclaration ->
                        sorter.addInstance(classDeclaration.qualifiedName!!.asString(), sorter.SorterInstance {
                            logger.warn("Processing: ${classDeclaration.qualifiedName?.asString()}")
                            val isStatic = when (classDeclaration.classKind) {
                                ClassKind.CLASS -> false
                                ClassKind.OBJECT -> true
                                else -> throw LuaBindingException(
                                    "Cannot process ${classDeclaration.qualifiedName?.asString()}: Provider not supports object"
                                )
                            }

                            val ifn = if (isStatic) nilFn
                            else KTProviderInstance(classDeclaration).also { fns.add(it) }.also {
                                val clName = classDeclaration.qualifiedName!!.asString()
                                if (inherit[clName] != null) it.inherit = inherit[clName]
                            }

                            classDeclaration.getDeclaredFunctions().mapNotNull {
                                it.getAnnotationsByType(
                                    LuaFunction::class
                                ).firstOrNull()?.let { annot -> it to annot }
                            }.forEach { (fn, annot) ->
                                val fnName = if (annot.name == "!") fn.simpleName.asString() else annot.name
                                val overloadFns = ifn.getFunction(fnName)
                                overloadFns.targets.add(
                                    BindTargetKt(
                                        fn, if (isStatic) null else classDeclaration, !annot.exportDoc
                                    )
                                )
                                files.add(classDeclaration.containingFile!!)
                            }
                        })
                    }

                    classes.forEach { current ->
                        current.superTypes.map { it.resolve().declaration }.filterIsInstance<KSClassDeclaration>()
                            .forEach { parent ->
                                if (parent.isAnnotationPresent(LuaProvider::class)) {
                                    inherit[current.qualifiedName!!.asString()] = parent.qualifiedName!!.asString()
                                    environment.logger.warn("Inherit: ${current.qualifiedName?.asString()} -> ${parent.qualifiedName?.asString()}")

                                    if (sorter[parent.qualifiedName!!.asString()] != null) sorter.setParent(
                                        parent.qualifiedName!!.asString(), current.qualifiedName!!.asString()
                                    )
                                }
                            }
                    }

                    sorter.process()
                }

                return resolver.getSymbolsWithAnnotation(luaProviderAnnotationName).filter { !it.validate() }.toList()
                    .also {
                        if (it.isNotEmpty()) logger.warn("Some class not processed: ${it.joinToString { it.location.toString() }}")
                    }
            }

            override fun finish() {
                super.finish()

                val pkg = environment.options["package_name"] ?: "me.ddayo.aris.gen"

                functions.entries.forEach { (clName, cls) ->
                    logger.warn(clName)
                    val luaCode = cls.joinToString("\n") { fn -> fn.luaBindings }

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
                        // Add all kotlin binding code(overloading resolved)
                        appendLine(cls.joinToString("\n") { fn -> fn.ktBindings })
                        // Add all lua code(overloading resolved here)
                        appendLine("        lua.load(\"\"\"$luaCode\"\"\")")
                        appendLine("lua.pCall(0, 0)")
                        // Add all metatable(for clean lua code and resolve inheritance)
                        appendLine(cls.joinToString("") { fn -> fn.metatableGenerated })
                        appendLine("}\n}")
                        // Add generated kotlin extension method
                        appendLine(cls.joinToString("\n") { v -> v.objectGenerated.toString() })
                    }.toString()

                    environment.codeGenerator.createNewFile(
                        dependencies = Dependencies(true, *(files).toTypedArray()), packageName = pkg, fileName = clName
                    ).writer().apply {
                        write(ktCode)
                        close()
                    }
                    if (environment.options["export_lua"] == "true") environment.codeGenerator.createNewFileByPath(
                        Dependencies(false), clName, "lua"
                    ).writer().apply {
                        write(luaCode)
                        close()
                    }
                    if (environment.options["export_doc"] == "true") environment.codeGenerator.createNewFileByPath(
                        Dependencies(false), "${clName}_doc", "md"
                    ).writer().apply {
                        write(cls.joinToString("\n\n") { it.docStrings })
                        close()
                    }
                }
            }
        }
    }
}
