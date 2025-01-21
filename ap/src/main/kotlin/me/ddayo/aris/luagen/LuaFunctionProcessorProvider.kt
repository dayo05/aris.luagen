@file:OptIn(KspExperimental::class)

package me.ddayo.aris.luagen

import com.google.devtools.ksp.*
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*


class LuaFunctionProcessorProvider : SymbolProcessorProvider {
    private abstract class AbstractBindTarget(
        val luaTargetName: String,
        val declaredClass: KSClassDeclaration?,
        val isPrivate: Boolean
    ) {
        abstract val returnResolved: KSType?
        val returnName by lazy { returnResolved?.declaration?.qualifiedName?.asString() }
        val isCoroutine by lazy { returnName == "me.ddayo.aris.CoroutineProvider.LuaCoroutineIntegration" }
        open val doc = StringBuilder()

        val processor = ArgumentManager.argFilters

        val preBuilder = StringBuilder()
        val mainBuilder = StringBuilder()
        var postBuilder = StringBuilder()

        private var svp = 1
        private var sip = 1

        /**
         * Append processed string to matching KSValueParameter into each builder
         * @param type type to append
         * @param vp actual KSValueParameter to append. this can be null if this mentions `this`
         */
        protected fun proc(type: KSType, vp: KSValueParameter?) {
            val _postBuilder = StringBuilder()
            val p = processor.first { it.isValid(type, vp) }
                .process(
                    preBuilder,
                    mainBuilder,
                    _postBuilder,
                    svp,
                    sip,
                    vp,
                    type.declaration as KSClassDeclaration
                )
            postBuilder = _postBuilder.append(postBuilder)
            svp = p.first
            sip = p.second
        }

        val ktCallString by lazy {
            StringBuilder().apply {
                appendLine("lua.push { lua ->")

                appendLine(preBuilder)
                appendLine(mainBuilder)
                appendLine(postBuilder)

                if (svp > 1)
                    appendLine("lua.pop(${svp - 1})")

                val returnNullable = returnResolved?.nullability == Nullability.NULLABLE
                fun handleNullable(str: String) = if (returnNullable) "rt?.let { rt -> $str } ?: lua.pushNil()" else str

                returnResolved?.let {
                    when {
                        parResolved.unitResolved.isAssignableFrom(it) -> appendLine("return@push 0")
                        parResolved.numberResolved.isAssignableFrom(it)
                                || parResolved.stringResolved.isAssignableFrom(it)
                                || parResolved.booleanResolved.isAssignableFrom(it) -> {
                            appendLine(handleNullable("lua.push(rt)"))
                            appendLine("return@push 1")
                        }

                        parResolved.staticDeclResolved.isAssignableFrom(it) -> {
                            appendLine(handleNullable("lua.pushJavaObject(rt)\nrt.toLua(engine, lua)"))
                            appendLine("return@push 1")
                        }

                        else -> appendLine("return@push LuaMain.pushNoInline(lua, rt)")
                    }
                } ?: run { appendLine("return@push 0") }
                appendLine("}")
            }
        }

        fun scoreCalcLua(fnName: String) = StringBuilder().apply {
            appendLine(
                """
if table_size >= ${svp - 1} then
    local task_score = ${svp - 1}
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

    private class BindTargetFnKt(
        luaTargetName: String,
        val funcCall: KSFunctionDeclaration,
        declaredClass: KSClassDeclaration?,
        isPrivate: Boolean
    ) : AbstractBindTarget(luaTargetName, declaredClass, isPrivate) {
        override val returnResolved = funcCall.returnType?.resolve()?.starProjection()

        val ptResolved: MutableList<Pair<KSValueParameter?, KSType>> =
            funcCall.parameters.map { it to it.type.resolve() }.toMutableList()

        val docSignature =
            funcCall.parameters.joinToString(", ") { "${it.name?.asString()}: ${parResolved.getLuaFriendlyName(it.type.resolve())}" }

        // override val doc = funcCall.docString ?: ""
        override val doc = StringBuilder().apply {
            if (declaredClass == null) append("## $luaTargetName(${docSignature})")
            else append("## ${declaredClass.simpleName.asString()}:$luaTargetName(${docSignature})")
            if (returnResolved != null && !returnResolved.isAssignableFrom(parResolved.unitResolved))
                appendLine(" -> ${parResolved.getLuaFriendlyName(returnResolved)}")
            else appendLine()
            funcCall.docString?.let { doc ->
                appendLine("```")
                append(' ')
                appendLine(doc.trim())
                appendLine("```")
            }
        }

        init {
            if (returnResolved?.let { parResolved.unitResolved.isAssignableFrom(it) } == false)
                mainBuilder.append("val rt = ")

            declaredClass?.let { cl ->
                mainBuilder.append("(")
                val ty = cl.asStarProjectedType()
                proc(ty, null)
                mainBuilder.append(").")
                    .append(funcCall.simpleName.asString())
            } ?: run {
                mainBuilder.append(funcCall.qualifiedName!!.asString())
            }
            mainBuilder.append("(")
            if (ptResolved.isNotEmpty()) {
                ptResolved.forEachIndexed { index, (vp, type) ->
                    if (index != 0) mainBuilder.append(", ")
                    proc(type, vp)
                }
            }
            mainBuilder.append(")")
        }
    }

    private class BindTargetPropertyGetterKt(
        luaTargetName: String,
        val property: KSPropertyDeclaration,
        declaredClass: KSClassDeclaration?,
        isPrivate: Boolean
    ) : AbstractBindTarget(luaTargetName, declaredClass, isPrivate) {
        override val returnResolved = property.type.resolve().starProjection()
        override val doc = StringBuilder().apply {
            if (declaredClass == null)
                appendLine("## $luaTargetName() -> ${parResolved.getLuaFriendlyName(returnResolved)}")
            else appendLine(
                "## ${declaredClass.simpleName.asString()}:$luaTargetName() -> ${
                    parResolved.getLuaFriendlyName(
                        returnResolved
                    )
                }"
            )

            property.docString?.let { doc ->
                appendLine("```")
                append(' ')
                appendLine(doc.trim())
                appendLine("```")
            }
        }

        init {
            mainBuilder.append("val rt = ")
            declaredClass?.let { cl ->
                mainBuilder.append("(")
                val ty = cl.asStarProjectedType()
                proc(ty, null)
                mainBuilder.append(").")
                    .append(property.simpleName.asString())
            } ?: run {
                mainBuilder.append(property.qualifiedName!!.asString())
            }
        }
    }

    private class BindTargetPropertySetterKt(
        luaTargetName: String,
        val property: KSPropertyDeclaration,
        declaredClass: KSClassDeclaration?,
        isPrivate: Boolean
    ) : AbstractBindTarget(luaTargetName, declaredClass, isPrivate) {
        override val returnResolved = parResolved.unitResolved
        val typeResolved = property.type.resolve()

        override val doc = StringBuilder().apply {
            if (declaredClass == null)
                appendLine("## $luaTargetName(new_value: ${parResolved.getLuaFriendlyName(typeResolved)})")
            else appendLine(
                "## ${declaredClass.simpleName.asString()}:$luaTargetName(new_value: ${
                    parResolved.getLuaFriendlyName(
                        typeResolved
                    )
                })"
            )

            property.docString?.let { doc ->
                appendLine("```")
                append(' ')
                appendLine(doc.trim())
                appendLine("```")
            }
        }

        init {
            declaredClass?.let { cl ->
                mainBuilder.append("(")
                val ty = cl.asStarProjectedType()
                proc(ty, null)
                mainBuilder.append(").")
                    .append(property.simpleName.asString())
            } ?: run {
                mainBuilder.append(property.qualifiedName!!.asString())
            }
            mainBuilder.append(" = ")
            proc(typeResolved, null)
        }
    }

    private abstract class AbstractBindTargetLua(
        val name: String,
        val targets: MutableList<AbstractBindTarget>
    ) {
        abstract val ktBind: StringBuilder
        abstract val luaBind: StringBuilder

        val docString by lazy {
            StringBuilder().apply {
                targets.forEach {
                    if (it.isPrivate) return@forEach
                    append(it.doc)
                }
            }
        }
    }

    private class BindStaticTargetLua(
        val library: String,
        name: String,
        targets: MutableList<AbstractBindTarget>
    ) : AbstractBindTargetLua(name, targets) {
        val librarySp by lazy {
            libraryFnCount.getOrPut(library) { 0 }
        }
        override val ktBind by lazy {
            StringBuilder().apply {
                append(
                    """    lua.getGlobal("$library")
    if(lua.isNoneOrNil(-1)) {
        lua.pop(1)
        lua.newTable()
    }
                        """
                )
                targets.forEachIndexed { index, bindTargetKt ->
                    append(bindTargetKt.ktCallString)
                    appendLine("lua.setField(-2, \"${name}_kt${index + librarySp}\")")
                }
                append("lua.setGlobal(\"$library\")")
                if (libraryFnCount[library] == librarySp)
                    libraryFnCount[library] = librarySp + targets.size
            }
        }

        override val luaBind by lazy {
            StringBuilder().apply {
                appendLine("function $library.$name(...)")
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
                            "$library.${name}_kt${index + librarySp}"
                        )
                    )
                }
                append(
                    """
    return sel_fn(...)
end
"""
                )
                if (libraryFnCount[library] == librarySp)
                    libraryFnCount[library] = librarySp + targets.size
            }
        }
    }

    private class BindInstanceTargetLua(
        val library: String,
        name: String,
        targets: MutableList<AbstractBindTarget>,
        val declaredClass: KSClassDeclaration
    ) : AbstractBindTargetLua(name, targets) {
        override val ktBind by lazy {
            StringBuilder().apply {
                targets.forEachIndexed { index, bindTargetKt ->
                    append(bindTargetKt.ktCallString)
                    appendLine("lua.setField(-2, \"${name}_kt${index}\")")
                }
            }
        }

        override val luaBind by lazy {
            StringBuilder().apply {
                appendLine(
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
                            "aris_${declaredClass.qualifiedName?.asString()?.replace('.', '_')}.${name}_kt${index}"
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
    }

    private abstract class AbstractKTProviderInstance(val inner: MutableMap<Pair<String, String>, AbstractBindTargetLua> = mutableMapOf()) {
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

        abstract fun getFunction(library: String, name: String): AbstractBindTargetLua

        open val objectGenerated = StringBuilder()
        open val refGenerated = StringBuilder()
        open val metatableGenerated = StringBuilder()
        var inherit: String? = null
        var inheritParent: String? = null
    }

    private class KTObjectProviderInstance : AbstractKTProviderInstance() {
        override fun getFunction(library: String, name: String) = inner.getOrPut(library to name) {
            BindStaticTargetLua(
                library, name, mutableListOf()
            )
        }
    }

    private class KTProviderInstance(val declaredClass: KSClassDeclaration) : AbstractKTProviderInstance() {
        override fun getFunction(library: String, name: String) = inner.getOrPut(library to name) {
            BindInstanceTargetLua(
                library, name, mutableListOf(), declaredClass
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
            appendLine("    override fun toLua(engine: LuaEngine, lua: Lua) {")
            appendLine(
                "        lua.refGet(engine.inner[\"aris_${
                    className.replace(".", "_")
                }_mt\"]!!)"
            )
            appendLine("        lua.setMetatable(-2)")
            appendLine("    }")
            appendLine("}")
        }

        override val refGenerated: StringBuilder
            get() = StringBuilder().apply {
                append("var aris_${className.replace('.', '_')}_mt = -1")
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
                                    lua.getGlobal("aris_${cln}_${it.second}")
                                    lua.setField(-2, "${it.second}")
                                    """
                    )
                }

                inherit?.let {
                    val inheritDecl = "aris_${it.replace(".", "_")}_mt".let {
                        if (inheritParent?.isNotBlank() == true) "$inheritParent.$it" else it
                    }
                    appendLine(
                        """
                                lua.newTable()
                                lua.refGet($inheritDecl)
                                lua.getField(-1, "__index")
                                lua.setField(-3, "__index")
                                lua.pop(1)
                                lua.setMetatable(-2)
                                """.trimIndent()
                    )
                }

                appendLine("lua.setField(-2, \"__index\")")
                appendLine("aris_${cln}_mt = lua.ref()")
                appendLine("engine.inner[\"aris_${cln}_mt\"] = aris_${cln}_mt")
            }
    }

    companion object {
        private val luaFunctionAnnotationName = LuaFunction::class.java.canonicalName
        private val luaProviderAnnotationName = LuaProvider::class.java.canonicalName
        internal lateinit var parResolved: ParameterCache private set
        internal lateinit var logger: KSPLogger private set
        val libraryFnCount = mutableMapOf<String, Int>()
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
                        classDeclaration.getAnnotationsByType(LuaProvider::class).forEach {
                            byProvider.getOrPut(it.className.let {
                                if (it == "!") defCln
                                else it
                            }) { mutableListOf() }.add(classDeclaration)
                        }
                    }
                }

                byProvider.forEach { (provider, classes) ->
                    val fns = functions.getOrPut(provider) { mutableListOf() }
                    val sorter = Sorter()
                    val inherit = mutableMapOf<String, String>()

                    val nilFn = KTObjectProviderInstance().also { fns.add(it) }
                    classes.forEach { classDeclaration ->
                        sorter.addInstance(classDeclaration.qualifiedName!!.asString(), sorter.SorterInstance {
                            logger.info("Processing: ${classDeclaration.qualifiedName?.asString()}")
                            val isStatic = when (classDeclaration.classKind) {
                                ClassKind.CLASS -> false
                                ClassKind.OBJECT -> true
                                else -> throw LuaBindingException(
                                    "Cannot process ${classDeclaration.qualifiedName?.asString()}: Provider not supports object"
                                )
                            }

                            classDeclaration.getAnnotationsByType(LuaProvider::class).forEach { providerAnnot ->
                                val ifn = if (isStatic) nilFn
                                else KTProviderInstance(classDeclaration).also { fns.add(it) }.also {
                                    val clName = classDeclaration.qualifiedName!!.asString()
                                    if (inherit[clName] != null) {
                                        it.inherit = inherit[clName]
                                        it.inheritParent = providerAnnot.inherit
                                    }
                                }

                                classDeclaration.getDeclaredFunctions().mapNotNull {
                                    it.getAnnotationsByType(
                                        LuaFunction::class
                                    ).firstOrNull()?.let { annot -> it to annot }
                                }.forEach { (fn, annot) ->
                                    val fnName = if (annot.name == "!") fn.simpleName.asString() else annot.name
                                    val library = if (annot.library == "_G") providerAnnot.library else annot.library
                                    val overloadFns = ifn.getFunction(library, fnName)
                                    overloadFns.targets.add(
                                        BindTargetFnKt(
                                            fnName, fn, if (isStatic) null else classDeclaration, !annot.exportDoc
                                        )
                                    )
                                    files.add(classDeclaration.containingFile!!)
                                }

                                classDeclaration.getDeclaredProperties().mapNotNull {
                                    it.getAnnotationsByType(
                                        LuaProperty::class
                                    ).firstOrNull()?.let { annot -> it to annot }
                                }.forEach { (fn, annot) ->
                                    val fnName = if (annot.name == "!") fn.simpleName.asString() else annot.name
                                    val library = if (annot.library == "_G") providerAnnot.library else annot.library
                                    if (annot.exportPropertySetter && fn.isMutable)
                                        ifn.getFunction(library, "set_$fnName").targets.add(
                                            BindTargetPropertySetterKt(
                                                "set_$fnName",
                                                fn,
                                                if (isStatic) null else classDeclaration,
                                                !annot.exportDoc
                                            )
                                        )
                                    ifn.getFunction(library, "get_$fnName").targets.add(
                                        BindTargetPropertyGetterKt(
                                            "get_$fnName",
                                            fn,
                                            if (isStatic) null else classDeclaration,
                                            !annot.exportDoc
                                        )
                                    )
                                    files.add(classDeclaration.containingFile!!)
                                }
                            }
                        })
                    }

                    classes.forEach { current ->
                        current.superTypes.map { it.resolve().declaration }.filterIsInstance<KSClassDeclaration>()
                            .forEach { parent ->
                                if (parent.isAnnotationPresent(LuaProvider::class)) {
                                    inherit[current.qualifiedName!!.asString()] = parent.qualifiedName!!.asString()
                                    environment.logger.info("Inherit: ${current.qualifiedName?.asString()} -> ${parent.qualifiedName?.asString()}")

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
                    logger.info("Generating: $clName")
                    val luaCode = cls.joinToString("\n") { fn -> fn.luaBindings }

                    val ktCode = StringBuilder().apply {
                        appendLine(
                            """package $pkg

import me.ddayo.aris.LuaMultiReturn
import party.iroiro.luajava.Lua
import party.iroiro.luajava.LuaException
import me.ddayo.aris.*

object $clName {"""
                        )
                        appendLine(cls.joinToString("\n") { fn -> fn.refGenerated })
                        appendLine(
                            """
    fun initEngine(engine: LuaEngine) {
        val lua = engine.lua
        val LuaMain = engine.luaMain 
"""
                        )
                        // Add all kotlin binding code(overloading resolved)
                        appendLine(cls.joinToString("\n") { fn -> fn.ktBindings })
                        // Add all lua code(overloading resolved here)
                        appendLine("        lua.load(\"\"\"$luaCode\"\"\")")
                        appendLine("lua.pCall(0, 0)")
                        // Add all metatable(for clean lua code and resolve inheritance)
                        appendLine(cls.joinToString("") { fn -> fn.metatableGenerated })
                        appendLine("}")
                        // Add generated kotlin extension method
                        appendLine(cls.joinToString("\n") { v -> v.objectGenerated.toString() })
                        appendLine("}")
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
