@file:OptIn(KspExperimental::class)

package me.ddayo.aris.luagen

import com.google.devtools.ksp.*
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText


class LuaFunctionProcessorProvider : SymbolProcessorProvider {
    private abstract class AbstractBindTarget(
        val luaTargetName: String,
        val declaredClass: KSClassDeclaration?,
        val isPrivate: Boolean
    ) {
        abstract val returnResolved: KSType?
        val returnName by lazy { returnResolved?.declaration?.qualifiedName?.asString() }
        val isCoroutine by lazy { returnName == "me.ddayo.aris.luagen.CoroutineProvider.LuaCoroutineIntegration" }
        open val doc = StringBuilder()
        abstract val apiParams: List<ApiSchemaParam>
        abstract val apiReturnType: String
        abstract val apiDescription: String?

        val processor = ArgumentManager.argFilters

        val preBuilder = StringBuilder()
        val mainBuilder = StringBuilder()
        var postBuilder = StringBuilder()

        private var svp = 1
        private var sip = 1

        val docSignatureBuilder = mutableListOf<String>()

        /**
         * Get valid argument for provided type and value parameter
         * @param type type to check
         * @param vp value parameter to get argument. this can be null in case of `this` or used for return
         */
        fun getProcessor(type: KSType, vp: KSValueParameter?): ArgumentManager.Argument =
            processor.first { it.isValid(type, vp) }

        fun getLuaFriendlyName(type: KSType) = mutableListOf<String>().also {
                try {
                    if(type.declaration is KSTypeParameter) {
                        it.add("Generic")
                        return@also
                    }
                    val processor = getProcessor(type, null)
                    processor.resolveDocSignature(null, type.declaration as KSClassDeclaration, it)
                } catch(e: Exception) {
                    logger.warn(e.stackTraceToString())
                }
            }.joinToString(", ")

        var classDeclarationBias = 0
            private set

        fun processClassDeclaration() = declaredClass?.let { cl ->
                mainBuilder.append("(")
                val ty = cl.asStarProjectedType()
                proc(getProcessor(ty, null), ty, null)
                mainBuilder.append(").")
                classDeclarationBias = svp - 1
                return@let mainBuilder
            }

        /**
         * Append processed string to matching KSValueParameter into each builder
         * @param arg expected argument type
         * @param type type to append
         * @param vp actual KSValueParameter to append. this can be null if this mentions `this`
         */
        protected fun proc(
            arg: ArgumentManager.Argument,
            type: KSType,
            vp: KSValueParameter?
        ) {
            val _postBuilder = StringBuilder()
            val p = arg
                .process(
                    preBuilder,
                    mainBuilder,
                    _postBuilder,
                    svp,
                    sip,
                    vp,
                    type.declaration as KSClassDeclaration
                )
            svp = p.first
            sip = p.second
            postBuilder = _postBuilder.append(postBuilder)
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
if table_size >= ${svp - 1 - classDeclarationBias} then
    local task_score = ${svp - 1 - classDeclarationBias}
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
        private val callbacksByParamName = funcCall.luaFunctionCallbackAnnotationsByParam()
        override val apiParams = funcCall.parameters.mapIndexedNotNull { index, param ->
            val parameterCallbacks = param.luaCallbackAnnotations()
            param.toApiSchemaParam(index, parameterCallbacks.ifEmpty { callbacksByParamName[param.name?.asString()] }.orEmpty())
        }
        override val apiReturnType = returnResolved.schemaTypeName()
        override val apiDescription = funcCall.docString?.trim()?.takeIf { it.isNotBlank() }

        val ptResolved: MutableList<Pair<KSValueParameter?, KSType>> =
            funcCall.parameters.map { it to it.type.resolve() }.toMutableList()

        init {
            if (returnResolved?.let { parResolved.unitResolved.isAssignableFrom(it) } == false)
                mainBuilder.append("val rt = ")

            processClassDeclaration()?.append(funcCall.simpleName.asString()) ?: mainBuilder.append(funcCall.qualifiedName!!.asString())
            mainBuilder.append("(")
            if (ptResolved.isNotEmpty()) {
                ptResolved.forEachIndexed { index, (vp, type) ->
                    if (index != 0) mainBuilder.append(", ")
                    val processor = getProcessor(type, vp)
                    proc(processor, type, vp)
                    processor.resolveDocSignatureWithName(vp, type.declaration as KSClassDeclaration, docSignatureBuilder)
                }
            }
            mainBuilder.append(")")
        }

        val docSignature =
            docSignatureBuilder.joinToString(", ")

        override val doc = StringBuilder().apply {
            if (declaredClass == null) append("## $luaTargetName(${docSignature})")
            else append("## ${declaredClass.simpleName.asString()}:$luaTargetName(${docSignature})")
            if (returnResolved != null && !returnResolved.isAssignableFrom(parResolved.unitResolved))
                appendLine(" -> ${getLuaFriendlyName(returnResolved)}")
            else appendLine()
            funcCall.docString?.let { doc ->
                appendLine("```")
                append(' ')
                appendLine(doc.trim())
                appendLine("```")
            }
        }

        // KSP2 closes the analysis-API scope after `process()` returns. Force
        // the parent's KSP-touching lazies (returnName, isCoroutine,
        // ktCallString) to materialize now, while we're still inside the
        // scope; without this `finish()` blows up reading
        // `returnResolved.declaration` etc.
        init {
            @Suppress("UNUSED_EXPRESSION") run {
                returnName; isCoroutine; ktCallString
            }
        }
    }

    private class BindTargetPropertyGetterKt(
        luaTargetName: String,
        val property: KSPropertyDeclaration,
        declaredClass: KSClassDeclaration?,
        isPrivate: Boolean
    ) : AbstractBindTarget(luaTargetName, declaredClass, isPrivate) {
        override val returnResolved = property.type.resolve().starProjection()
        override val apiParams = emptyList<ApiSchemaParam>()
        override val apiReturnType = returnResolved.schemaTypeName()
        override val apiDescription = property.docString?.trim()?.takeIf { it.isNotBlank() }
        override val doc = StringBuilder().apply {
            if (declaredClass == null)
                appendLine("## $luaTargetName() -> ${getLuaFriendlyName(returnResolved)}")
            else appendLine(
                "## ${declaredClass.simpleName.asString()}:$luaTargetName() -> ${getLuaFriendlyName(returnResolved)}"
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
            processClassDeclaration()?.append(property.simpleName.asString()) ?: mainBuilder.append(property.qualifiedName!!.asString())
        }

        // See BindTargetFnKt — same KSP2 lifetime workaround.
        init {
            @Suppress("UNUSED_EXPRESSION") run {
                returnName; isCoroutine; ktCallString
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
        override val apiParams = listOf(ApiSchemaParam("new_value", typeResolved.schemaTypeName()))
        override val apiReturnType = "nil"
        override val apiDescription = property.docString?.trim()?.takeIf { it.isNotBlank() }

        override val doc = StringBuilder().apply {
            if (declaredClass == null)
                appendLine("## $luaTargetName(new_value: ${getLuaFriendlyName(typeResolved)})")
            else appendLine(
                "## ${declaredClass.simpleName.asString()}:$luaTargetName(new_value: ${getLuaFriendlyName(typeResolved)})"
            )

            property.docString?.let { doc ->
                appendLine("```")
                append(' ')
                appendLine(doc.trim())
                appendLine("```")
            }
        }

        init {
            processClassDeclaration()?.append(property.simpleName.asString()) ?: mainBuilder.append(property.qualifiedName!!.asString())

            mainBuilder.append(" = ")
            val processor = getProcessor(typeResolved, null)
            proc(processor, typeResolved, null)
        }

        // See BindTargetFnKt — same KSP2 lifetime workaround.
        init {
            @Suppress("UNUSED_EXPRESSION") run {
                returnName; isCoroutine; ktCallString
            }
        }
    }

    private interface ISourceProvider {
        val ktBind: StringBuilder
        val luaBind: StringBuilder
        val docString: StringBuilder
    }

    private class BindStaticLibraryTargetLua(
        val library: String,
        val targets: MutableMap<String, BindStaticTargetLua> = mutableMapOf(),
        val children: MutableMap<String, BindStaticLibraryTargetLua> = mutableMapOf()
    ) : ISourceProvider {
        override val ktBind: StringBuilder by lazy {
            StringBuilder().apply {
                append(
                    """    lua.getField(-1, "$library")
    if(lua.isNoneOrNil(-1)) {
        lua.pop(1)
        lua.newTable()
    }
                        """
                )
                targets.values.forEach { append(it.ktBind) }
                children.values.forEach { append(it.ktBind) }
                appendLine("lua.setField(-2, \"$library\")")
            }
        }

        override val luaBind: StringBuilder by lazy {
            StringBuilder().apply {
                targets.values.forEach { append(it.luaBind) }
                children.values.forEach { append(it.luaBind) }
            }
        }

        override val docString: StringBuilder by lazy {
            StringBuilder().apply {
                targets.values.forEach { append(it.docString) }
                children.values.forEach { append(it.docString) }
            }
        }
    }

    private interface IBindTargetProvider {
        val targets: MutableList<AbstractBindTarget>
    }

    private class BindStaticTargetLua(
        // this must include full library name
        val library: String,
        name: String,
        override val targets: MutableList<AbstractBindTarget> = mutableListOf(),
    ) : ISourceProvider, IBindTargetProvider {
        override val ktBind: StringBuilder by lazy {
            StringBuilder().apply {
                targets.forEachIndexed { index, bindTargetKt ->
                    append(bindTargetKt.ktCallString)
                    appendLine("lua.setField(-2, \"${name}_kt${index}\")")
                }
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
                            "$library.${name}_kt${index}"
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

        override val docString by lazy {
            StringBuilder().apply {
                targets.forEach {
                    if (it.isPrivate) return@forEach
                    append(it.doc)
                }
            }
        }
    }

    private class BindInstanceTargetLua(
        name: String,
        override val targets: MutableList<AbstractBindTarget>,
        val declaredClass: KSClassDeclaration
    ) : ISourceProvider, IBindTargetProvider {
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

        override val docString by lazy {
            StringBuilder().apply {
                targets.forEach {
                    if (it.isPrivate) return@forEach
                    append(it.doc)
                }
            }
        }
    }

    private abstract class AbstractKTProviderInstance<T : ISourceProvider, F : IBindTargetProvider>() :
        ISourceProvider {
        val inner: MutableMap<String, T> = mutableMapOf()
        override val luaBind by lazy {
            StringBuilder().apply {
                inner.values.joinTo(this, "\n") { it.luaBind }
            }
        }

        override val ktBind by lazy {
            StringBuilder().apply {
                inner.values.joinTo(this, "\n") { it.ktBind }
            }
        }

        override val docString by lazy {
            StringBuilder().apply {
                inner.values.map { it.docString }.filter { it.isNotBlank() }.joinTo(this, "\n\n")
            }
        }

        abstract fun getFunction(library: String, name: String): F

        open val objectGenerated = StringBuilder()
        open val metatableGenerated = StringBuilder()
        var inherit: String? = null
        var inheritParent: String? = null
    }

    private data class ApiSchemaType(
        val name: String,
        val displayName: String,
        val description: String? = null,
        val extends: Set<String> = emptySet()
    )

    private data class ApiSchemaCallback(
        val params: List<ApiSchemaParam>,
        val returns: String
    )

    private data class ApiSchemaParam(
        val name: String,
        val type: String,
        val callbacks: List<ApiSchemaCallback> = emptyList()
    )

    private data class ApiSchemaFunction(
        val namespace: String,
        val name: String,
        val luaName: String,
        val i18nKey: String,
        val params: List<ApiSchemaParam>,
        val returns: String,
        val description: String?
    )

    private data class ApiSchemaMethod(
        val ownerType: String,
        val name: String,
        val i18nKey: String,
        val params: List<ApiSchemaParam>,
        val returns: String,
        val description: String?
    )

    private data class ApiI18nEntry(
        val displayName: String,
        val description: String? = null,
        val rawJson: String? = null
    )

    private class KTObjectProviderInstance :
        AbstractKTProviderInstance<BindStaticLibraryTargetLua, BindStaticTargetLua>() {
        override fun getFunction(library: String, name: String): BindStaticTargetLua {
            val sp = library.split(".")
            var cur = inner.getOrPut(sp[0]) { BindStaticLibraryTargetLua(sp[0]) }
            for (i in 1 until (sp.size))
                cur = cur.children.getOrPut(sp[i]) { BindStaticLibraryTargetLua(sp[i]) }
            return cur.targets.getOrPut(name) { BindStaticTargetLua(library, name) }
        }

        override val ktBind: StringBuilder by lazy {
            StringBuilder().apply {
                appendLine("lua.getGlobal(\"_G\")")
                append(super.ktBind)
                appendLine("lua.pop(1)")
            }
        }
    }

    private class KTProviderInstance(val declaredClass: KSClassDeclaration) :
        AbstractKTProviderInstance<BindInstanceTargetLua, BindInstanceTargetLua>() {
        override fun getFunction(library: String, name: String) = inner.getOrPut(name) {
            BindInstanceTargetLua(
                name, mutableListOf(), declaredClass
            )
        }

        val className = declaredClass.qualifiedName!!.asString()
        val simpleName = declaredClass.simpleName.asString()

        override val ktBind: StringBuilder
            get() = StringBuilder().apply {
                appendLine("lua.newTable()")

                append(super.ktBind)

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
                                lua.push("$cln")
                                lua.setField(-2, "type")
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
                    val inheritDecl = "engine.inner[\"aris_${it.replace(".", "_")}_mt\"]!!"
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
                appendLine("engine.inner[\"aris_${cln}_mt\"] = lua.ref()")
            }
    }

    companion object {
        private val luaFunctionAnnotationName = LuaFunction::class.java.canonicalName
        private val luaProviderAnnotationName = LuaProvider::class.java.canonicalName
        internal lateinit var parResolved: ParameterCache private set
        internal lateinit var logger: KSPLogger private set

        private val primitiveSchemaTypes = setOf("nil", "string", "number", "boolean", "function", "any")
        private val luaCallbackAnnotationName = LuaCallback::class.java.canonicalName
        private val luaCallbackParamAnnotationName = LuaCallbackParam::class.java.canonicalName

        private fun KSType?.schemaTypeName(): String {
            val type = this ?: return "nil"
            val decl = type.declaration
            if (decl is KSTypeParameter) return "any"
            val qualified = decl.qualifiedName?.asString() ?: decl.simpleName.asString()
            return when (qualified) {
                "kotlin.Unit" -> "nil"
                "kotlin.String", "java.lang.String" -> "string"
                "kotlin.Boolean", "java.lang.Boolean" -> "boolean"
                "kotlin.Byte", "kotlin.Short", "kotlin.Int", "kotlin.Long",
                "kotlin.Float", "kotlin.Double",
                "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long",
                "java.lang.Float", "java.lang.Double" -> "number"
                "kotlin.Function", "kotlin.Function0", "kotlin.Function1", "kotlin.Function2" -> "function"
                "kotlin.Any", "java.lang.Object" -> "any"
                else -> qualified
            }
        }

        private fun KSFunctionDeclaration.luaFunctionCallbackAnnotationsByParam(): Map<String, List<ApiSchemaCallback>> {
            val callbacks = annotations
                .firstOrNull { it.shortName.asString() == "LuaFunction" || it.annotationType.resolve().declaration.qualifiedName?.asString() == luaFunctionAnnotationName }
                ?.arguments
                ?.firstOrNull { it.name?.asString() == "callbacks" }
                ?.value as? List<*>
                ?: return emptyMap()
            return callbacks
                .mapNotNull { it as? KSAnnotation }
                .mapNotNull { callback ->
                    val param = callback.argumentValue<String>("param")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    param to callback.toApiSchemaCallback()
                }
                .groupBy({ it.first }, { it.second })
        }

        private fun KSValueParameter.luaCallbackAnnotations(): List<ApiSchemaCallback> =
            annotations
                .filter { it.shortName.asString() == "LuaCallback" || it.annotationType.resolve().declaration.qualifiedName?.asString() == luaCallbackAnnotationName }
                .map { it.toApiSchemaCallback() }
                .toList()

        private fun KSAnnotation.toApiSchemaCallback(): ApiSchemaCallback {
            val params = (argumentValue<List<*>>("params") ?: emptyList<Any?>())
                .mapNotNull { it as? KSAnnotation }
                .mapNotNull { it.toApiSchemaParam() }
            val returns = argumentValue<String>("returns")?.takeIf { it.isNotBlank() } ?: "nil"
            return ApiSchemaCallback(params, returns)
        }

        private fun KSAnnotation.toApiSchemaParam(): ApiSchemaParam? {
            if (annotationType.resolve().declaration.qualifiedName?.asString() != luaCallbackParamAnnotationName &&
                shortName.asString() != "LuaCallbackParam"
            ) return null
            val name = argumentValue<String>("name")?.takeIf { it.isNotBlank() } ?: return null
            return ApiSchemaParam(name, callbackParamSchemaTypeName())
        }

        private fun KSAnnotation.callbackParamSchemaTypeName(): String {
            argumentValue<String>("typeName")?.takeIf { it.isNotBlank() }?.let { return it }
            val luaType = when (val value = arguments.firstOrNull { it.name?.asString() == "luaType" }?.value) {
                is KSName -> value.getShortName()
                is KSClassDeclaration -> value.simpleName.asString()
                else -> value?.toString()
            }
            if (luaType != null && luaType != "CUSTOM") return luaType.lowercase()
            val type = argumentValue<KSType>("type") ?: return "any"
            return type.schemaTypeName()
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> KSAnnotation.argumentValue(name: String): T? =
            arguments.firstOrNull { it.name?.asString() == name }?.value as? T

        private fun KSValueParameter.toApiSchemaParam(index: Int, callbacks: List<ApiSchemaCallback> = emptyList()): ApiSchemaParam? {
            val resolved = type.resolve()
            val docTypes = mutableListOf<String>()
            val processor = ArgumentManager.argFilters.first { it.isValid(resolved, this) }
            processor.resolveDocSignature(this, resolved.declaration as KSClassDeclaration, docTypes)
            val docType = docTypes.singleOrNull()?.takeIf { it.isNotBlank() } ?: docTypes.joinToString(", ").takeIf { it.isNotBlank() } ?: return null
            val schemaType = if (docType in primitiveSchemaTypes) docType else resolved.schemaTypeName()
            return ApiSchemaParam(name?.asString() ?: "arg${index + 1}", schemaType, callbacks)
        }
    }

    @OptIn(KspExperimental::class)
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        logger = environment.logger
        try {
            return object : SymbolProcessor {
                val files = mutableSetOf<KSFile>()

                val functions = mutableMapOf<String, MutableList<AbstractKTProviderInstance<*, *>>>()

                override fun process(resolver: Resolver): List<KSAnnotated> {
                    parResolved = ParameterCache.init(resolver)
                    val defCln = environment.options["default_class_name"] ?: "LuaGenerated"

                    val byProvider = mutableMapOf<String, MutableList<Pair<LuaProvider, KSClassDeclaration>>>()
                    resolver.getSymbolsWithAnnotation(luaProviderAnnotationName).let { providers ->
                        providers.mapNotNull { it as? KSClassDeclaration }.forEach { classDeclaration ->
                            classDeclaration.getAnnotationsByType(LuaProvider::class).forEach {
                                byProvider.getOrPut(it.className.let {
                                    if (it == "!") defCln
                                    else it
                                }) { mutableListOf() }.add(it to classDeclaration)
                            }
                        }
                    }

                    byProvider.forEach { (provider, classes) ->
                        val fns = functions.getOrPut(provider) { mutableListOf() }
                        val sorter = Sorter()
                        val inherit = mutableMapOf<String, String>()

                        val nilFn = KTObjectProviderInstance().also { fns.add(it) }
                        classes.forEach { (providerAnnot, classDeclaration) ->
                            sorter.addInstance(classDeclaration.qualifiedName!!.asString(), sorter.SorterInstance {
                                logger.info("Processing: ${classDeclaration.qualifiedName?.asString()}")
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
                                    ifn.getFunction(library, fnName).targets.add(
                                        BindTargetFnKt(
                                            if (isStatic && library != "_G") "$library.$fnName" else fnName,
                                            fn,
                                            if (isStatic) null else classDeclaration,
                                            !annot.exportDoc
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
                            })
                        }

                        classes.forEach { (_, current) ->
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

                    return resolver.getSymbolsWithAnnotation(luaProviderAnnotationName).filter { !it.validate() }
                        .toList()
                        .also {
                            if (it.isNotEmpty()) logger.warn("Some class not processed: ${it.joinToString { it.location.toString() }}")
                        }
                }

                override fun finish() {
                    super.finish()

                    val pkg = environment.options["package_name"] ?: "me.ddayo.aris.gen"
                    val exportApiSchema = environment.options["export_api_schema"] == "true"
                    val apiContextsRequired = environment.options["api_contexts_required"] == "true"
                    val apiDisplayLangs = environment.options["api_display_lang"]
                        ?.split(",", "|")
                        ?.map { it.trim() }
                        ?.filter { it.isNotBlank() }
                        ?: emptyList()
                    val hasApiContextOptions = environment.options.keys.any { it.startsWith("api_contexts.") }
                    val i18nProviders = mutableListOf<AbstractKTProviderInstance<*, *>>()

                    functions.entries.forEach { (clName, cls) ->
                        logger.info("Generating: $clName")
                        val apiContexts = parseApiContexts(clName, environment.options)
                        if (exportApiSchema && apiContextsRequired && apiContexts.isEmpty()) {
                            throw LuaBindingException("Missing KSP option api_contexts.$clName while api_contexts_required=true")
                        }
                        if (!hasApiContextOptions || apiContexts.isNotEmpty()) {
                            i18nProviders += cls
                        }
                        val luaCode = cls.joinToString("\n") { fn -> fn.luaBind }

                        val ktCode = StringBuilder().apply {
                            appendLine(
                                """package $pkg

import party.iroiro.luajava.Lua
import party.iroiro.luajava.LuaException
import me.ddayo.aris.luagen.*

object $clName {"""
                            )
                            appendLine(
                                """
    fun initEngine(engine: LuaEngine) {
        val lua = engine.lua
        val LuaMain = engine.luaMain 
"""
                            )
                            // Add all kotlin binding code(overloading resolved)
                            appendLine(cls.joinToString("\n") { fn -> fn.ktBind })
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
                            dependencies = Dependencies(true, *(files).toTypedArray()),
                            packageName = pkg,
                            fileName = clName
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
                            write(cls.joinToString("\n\n") { it.docString })
                            close()
                        }
                        if (exportApiSchema) environment.codeGenerator.createNewFileByPath(
                            Dependencies(false), "apis/$clName", "json"
                        ).writer().apply {
                            write(buildApiSchemaJson(clName, cls, apiContexts))
                            close()
                        }
                    }

                    if (apiDisplayLangs.isNotEmpty()) {
                        writeDefaultI18nFiles(apiDisplayLangs, i18nProviders)
                    }
                }
            }
        } catch(e: Exception) {
            logger.error(e.stackTraceToString())
            throw e
        }
    }

    private fun buildApiSchemaJson(
        generatedClassName: String,
        providers: List<AbstractKTProviderInstance<*, *>>,
        contexts: List<String>
    ): String {
        val types = linkedMapOf<String, ApiSchemaType>()
        val functions = mutableListOf<ApiSchemaFunction>()
        val methods = mutableListOf<ApiSchemaMethod>()

        fun registerType(typeName: String) {
            if (typeName in primitiveSchemaTypes) return
            types.putIfAbsent(typeName, ApiSchemaType(typeName, typeName.substringAfterLast('.')))
        }

        fun registerReturnAndParams(returnType: String, params: List<ApiSchemaParam>) {
            fun registerParam(param: ApiSchemaParam) {
                registerType(param.type)
                param.callbacks.forEach { callback ->
                    registerType(callback.returns)
                    callback.params.forEach(::registerParam)
                }
            }
            registerType(returnType)
            params.forEach(::registerParam)
        }

        providers.forEach { provider ->
            when (provider) {
                is KTObjectProviderInstance -> {
                    provider.schemaFunctions().forEach { function ->
                        registerReturnAndParams(function.returns, function.params)
                        functions.add(function)
                    }
                }
                is KTProviderInstance -> {
                    registerType(provider.className)
                    provider.inherit?.let { parentType ->
                        registerType(parentType)
                        types[provider.className] = ApiSchemaType(
                            name = provider.className,
                            displayName = provider.simpleName,
                            description = provider.declaredClass.docString?.trim()?.takeIf { it.isNotBlank() },
                            extends = setOf(parentType)
                        )
                    } ?: run {
                        types[provider.className] = ApiSchemaType(
                            provider.className,
                            provider.simpleName,
                            provider.declaredClass.docString?.trim()?.takeIf { it.isNotBlank() }
                        )
                    }
                    provider.schemaMethods().forEach { method ->
                        registerReturnAndParams(method.returns, method.params)
                        methods.add(method)
                    }
                }
            }
        }

        return buildString {
            appendLine("{")
            appendLine("  \"version\": 1,")
            appendLine("  \"name\": ${jsonString(generatedClassName)},")
            appendLine("  \"generatedClassName\": ${jsonString(generatedClassName)},")
            appendLine("  \"contexts\": ${jsonStringArray(contexts)},")
            appendLine("  \"types\": [")
            append(types.values.sortedBy { it.name }.joinToString(",\n") { type ->
                buildString {
                    appendLine("    {")
                    appendLine("      \"name\": ${jsonString(type.name)},")
                    appendLine("      \"displayName\": ${jsonString(type.displayName)},")
                    appendLine("      \"description\": ${jsonNullableString(type.description)},")
                    append("      \"extends\": ${jsonStringArray(type.extends.sorted())}")
                    append("\n    }")
                }
            })
            appendLine()
            appendLine("  ],")
            appendLine("  \"functions\": [")
            append(functions.joinToString(",\n") { it.toJson() })
            appendLine()
            appendLine("  ],")
            appendLine("  \"methods\": [")
            append(methods.joinToString(",\n") { it.toJson() })
            appendLine()
            appendLine("  ]")
            appendLine("}")
        }
    }

    private fun parseApiContexts(providerName: String, options: Map<String, String>): List<String> =
        options["api_contexts.$providerName"]
            ?.split(",", "|")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    private fun KTObjectProviderInstance.schemaFunctions(): List<ApiSchemaFunction> =
        inner.flatMap { (_, library) -> library.schemaFunctions() }

    private fun BindStaticLibraryTargetLua.schemaFunctions(): List<ApiSchemaFunction> {
        val direct = targets.flatMap { (name, target) ->
            target.targets.mapNotNull { binding ->
                binding.toApiFunction(library, name)
            }
        }
        return direct + children.flatMap { (_, child) -> child.schemaFunctions() }
    }

    private fun KTProviderInstance.schemaMethods(): List<ApiSchemaMethod> =
        inner.flatMap { (name, target) ->
            target.targets.mapNotNull { binding ->
                binding.toApiMethod(className, name)
            }
        }

    private fun AbstractBindTarget.toApiFunction(library: String, name: String): ApiSchemaFunction? {
        if (isPrivate) return null
        val namespace = if (library == "_G") "" else library
        val luaName = if (namespace.isBlank()) name else "$namespace.$name"
        return ApiSchemaFunction(
            namespace = namespace,
            name = name,
            luaName = luaName,
            i18nKey = "$luaName(${apiParams.joinToString(",") { it.type }})",
            params = apiParams,
            returns = apiReturnType,
            description = apiDescription
        )
    }

    private fun AbstractBindTarget.toApiMethod(ownerType: String, name: String): ApiSchemaMethod? {
        if (isPrivate) return null
        return ApiSchemaMethod(
            ownerType = ownerType,
            name = name,
            i18nKey = "$ownerType:$name(${apiParams.joinToString(",") { it.type }})",
            params = apiParams,
            returns = apiReturnType,
            description = apiDescription
        )
    }

    private fun buildI18nEntries(providers: List<AbstractKTProviderInstance<*, *>>): Map<String, ApiI18nEntry> {
        val entries = linkedMapOf<String, ApiI18nEntry>()

        providers.forEach { provider ->
            when (provider) {
                is KTObjectProviderInstance -> {
                    provider.schemaFunctions().forEach { function ->
                        registerI18nTypes(function.returns, function.params, entries)
                        entries.putIfAbsent(
                            function.i18nKey,
                            ApiI18nEntry(defaultDisplayName(function.name), function.description)
                        )
                    }
                }
                is KTProviderInstance -> {
                    entries.putIfAbsent(
                        provider.className,
                        ApiI18nEntry(
                            provider.simpleName,
                            provider.declaredClass.docString?.trim()?.takeIf { it.isNotBlank() }
                        )
                    )
                    provider.inherit?.let { parentType ->
                        entries.putIfAbsent(parentType, ApiI18nEntry(parentType.substringAfterLast('.')))
                    }
                    provider.schemaMethods().forEach { method ->
                        registerI18nTypes(method.returns, method.params, entries)
                        entries.putIfAbsent(
                            method.i18nKey,
                            ApiI18nEntry(defaultDisplayName(method.name), method.description)
                        )
                    }
                }
            }
        }

        return entries.toSortedMap()
    }

    private fun registerI18nTypes(
        returnType: String,
        params: List<ApiSchemaParam>,
        entries: MutableMap<String, ApiI18nEntry>
    ) {
        fun register(type: String) {
            if (type !in primitiveSchemaTypes) entries.putIfAbsent(type, ApiI18nEntry(type.substringAfterLast('.')))
        }
        fun registerParam(param: ApiSchemaParam) {
            register(param.type)
            param.callbacks.forEach { callback ->
                register(callback.returns)
                callback.params.forEach(::registerParam)
            }
        }
        register(returnType)
        params.forEach(::registerParam)
    }

    private fun writeDefaultI18nFiles(langs: List<String>, providers: List<AbstractKTProviderInstance<*, *>>) {
        val defaults = buildI18nEntries(providers)
        if (defaults.isEmpty()) return

        val dir = Path.of(System.getProperty("user.dir"), "apis", "i18n")
        Files.createDirectories(dir)

        langs.distinct().forEach { lang ->
            val target = dir.resolve("$lang.json")
            val existing = if (target.exists()) parseTopLevelJsonEntries(target.readText()) else emptyMap()
            val merged = defaults.toMutableMap()
            existing.forEach { (key, value) ->
                val generated = defaults[key]
                merged[key] = if (value.rawJson == null && value.description == null && generated?.description != null) {
                    value.copy(description = generated.description)
                } else {
                    value
                }
            }
            target.writeText(toJsonObject(merged.toSortedMap(), defaultLanguage = "en"))
        }
    }

    private fun defaultDisplayName(name: String): String =
        name.split('_', '-', '.')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase() } }

    private fun ApiSchemaFunction.toJson() = buildString {
        appendLine("    {")
        appendLine("      \"kind\": \"function\",")
        appendLine("      \"namespace\": ${jsonString(namespace)},")
        appendLine("      \"name\": ${jsonString(name)},")
        appendLine("      \"luaName\": ${jsonString(luaName)},")
        appendLine("      \"i18nKey\": ${jsonString(i18nKey)},")
        appendLine("      \"params\": ${params.toJson()},")
        appendLine("      \"returns\": ${jsonString(returns)},")
        append("      \"description\": ${jsonNullableString(description)}")
        append("\n    }")
    }

    private fun ApiSchemaMethod.toJson() = buildString {
        appendLine("    {")
        appendLine("      \"kind\": \"method\",")
        appendLine("      \"ownerType\": ${jsonString(ownerType)},")
        appendLine("      \"name\": ${jsonString(name)},")
        appendLine("      \"i18nKey\": ${jsonString(i18nKey)},")
        appendLine("      \"params\": ${params.toJson()},")
        appendLine("      \"returns\": ${jsonString(returns)},")
        append("      \"description\": ${jsonNullableString(description)}")
        append("\n    }")
    }

    private fun List<ApiSchemaParam>.toJson(): String =
        joinToString(prefix = "[", postfix = "]") { it.toJson() }

    private fun ApiSchemaParam.toJson(): String =
        buildString {
            append("{\"name\": ${jsonString(name)}, \"type\": ${jsonString(type)}")
            callbacks.firstOrNull()?.let { append(", \"callback\": ${it.toJson()}") }
            if (callbacks.isNotEmpty()) append(", \"callbacks\": ${callbacks.toCallbacksJson()}")
            append("}")
        }

    private fun List<ApiSchemaCallback>.toCallbacksJson(): String =
        joinToString(prefix = "[", postfix = "]") { it.toJson() }

    private fun ApiSchemaCallback.toJson(): String =
        "{\"params\": ${params.toJson()}, \"returns\": ${jsonString(returns)}}"

    private fun jsonNullableString(value: String?): String =
        value?.let { jsonString(it) } ?: "null"

    private fun jsonStringArray(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { jsonString(it) }

    private fun jsonString(value: String): String =
        buildString {
            append('"')
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (ch.code < 0x20) append("\\u%04x".format(ch.code))
                        else append(ch)
                    }
                }
            }
            append('"')
        }

    private fun parseTopLevelJsonEntries(json: String): Map<String, ApiI18nEntry> {
        val result = linkedMapOf<String, ApiI18nEntry>()
        var index = json.indexOf('{') + 1
        while (index > 0 && index < json.length) {
            index = json.skipWhitespace(index)
            if (index >= json.length || json[index] == '}') break
            if (json[index] != '"') break
            val keyEnd = json.scanJsonStringEnd(index)
            val key = unescapeJsonString(json.substring(index + 1, keyEnd))
            index = json.skipWhitespace(keyEnd + 1)
            if (index >= json.length || json[index] != ':') break
            index = json.skipWhitespace(index + 1)
            val valueStart = index
            index = json.scanJsonValueEnd(index)
            val rawValue = json.substring(valueStart, index).trim()
            if (!key.startsWith("__")) result[key] = if (rawValue.startsWith("\"") && rawValue.endsWith("\"")) {
                ApiI18nEntry(unescapeJsonString(rawValue.substring(1, rawValue.length - 1)).trimWrappedQuotes())
            } else {
                ApiI18nEntry(displayName = "", rawJson = rawValue)
            }
            index = json.skipWhitespace(index)
            if (index < json.length && json[index] == ',') index += 1
        }
        return result
    }

    private fun unescapeJsonString(value: String): String =
        value
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")

    private fun String.trimWrappedQuotes(): String =
        if (length >= 2 && startsWith("\"") && endsWith("\"")) substring(1, length - 1) else this

    private fun toJsonObject(values: Map<String, ApiI18nEntry>, defaultLanguage: String): String = buildString {
        appendLine("{")
        append("  ${jsonString("__defaultLanguage")}: ${jsonString(defaultLanguage)}")
        if (values.isNotEmpty()) append(',')
        appendLine()
        values.entries.forEachIndexed { index, (key, value) ->
            append("  ${jsonString(key)}: ${i18nEntryJson(value)}")
            if (index != values.size - 1) append(',')
            appendLine()
        }
        appendLine("}")
    }

    private fun i18nEntryJson(entry: ApiI18nEntry): String =
        entry.rawJson ?: entry.description?.let { desc ->
            "{ \"displayName\": ${jsonString(entry.displayName)}, \"description\": ${jsonString(desc)} }"
        } ?: jsonString(entry.displayName)

    private fun String.skipWhitespace(start: Int): Int {
        var index = start
        while (index < length && this[index].isWhitespace()) index += 1
        return index
    }

    private fun String.scanJsonStringEnd(start: Int): Int {
        var index = start + 1
        var escaped = false
        while (index < length) {
            val ch = this[index]
            if (escaped) escaped = false
            else if (ch == '\\') escaped = true
            else if (ch == '"') return index
            index += 1
        }
        return length - 1
    }

    private fun String.scanJsonValueEnd(start: Int): Int {
        if (start >= length) return start
        if (this[start] == '"') return scanJsonStringEnd(start) + 1
        if (this[start] == '{') {
            var depth = 0
            var index = start
            var inString = false
            var escaped = false
            while (index < length) {
                val ch = this[index]
                if (inString) {
                    if (escaped) escaped = false
                    else if (ch == '\\') escaped = true
                    else if (ch == '"') inString = false
                } else {
                    if (ch == '"') inString = true
                    else if (ch == '{') depth += 1
                    else if (ch == '}') {
                        depth -= 1
                        if (depth == 0) return index + 1
                    }
                }
                index += 1
            }
            return length
        }
        var index = start
        while (index < length && this[index] != ',' && this[index] != '}') index += 1
        return index
    }

}
