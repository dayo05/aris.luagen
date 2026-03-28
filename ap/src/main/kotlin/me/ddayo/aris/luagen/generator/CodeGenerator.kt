package me.ddayo.aris.luagen.generator

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Nullability
import com.squareup.kotlinpoet.*
import me.ddayo.aris.luagen.ArgumentManager
import me.ddayo.aris.luagen.ParameterCache
import me.ddayo.aris.luagen.model.*

internal class CodeGenerator(
    private val paramCache: ParameterCache,
    private val packageName: String,
    private val logger: KSPLogger
) {
    private val libraryFnCount = mutableMapOf<String, Int>()

    fun generate(group: ProviderGroupModel): GeneratedOutput {
        val classInstances = group.instances.filterIsInstance<ProviderInstance.ClassInstance>()

        val mainObject = TypeSpec.objectBuilder(group.className)

        // Metatable reference properties
        classInstances.forEach { classInfo ->
            mainObject.addProperty(
                PropertySpec.builder(mtVarName(classInfo.qualifiedName), INT)
                    .mutable(true)
                    .initializer("-1")
                    .build()
            )
        }

        // Collect all lua code
        val allLuaCode = StringBuilder()

        // Build initEngine function body
        val initBody = CodeBlock.builder()
            .addStatement("val lua = engine.lua")
            .addStatement("val LuaMain = engine.luaMain")

        // Generate bindings for each provider instance
        group.instances.forEach { instance ->
            when (instance) {
                is ProviderInstance.StaticInstance ->
                    generateStaticBindings(instance, initBody, allLuaCode)
                is ProviderInstance.ClassInstance ->
                    generateInstanceBindings(instance, initBody, allLuaCode)
            }
        }

        // Load all Lua code
        if (allLuaCode.isNotBlank()) {
            initBody.addStatement("lua.load(%S)", allLuaCode.toString())
            initBody.addStatement("lua.pCall(0, 0)")
        }

        // Generate metatables
        classInstances.forEach { classInfo ->
            generateMetatable(classInfo, initBody)
        }

        mainObject.addFunction(
            FunSpec.builder("initEngine")
                .addParameter("engine", ClassName("me.ddayo.aris", "LuaEngine"))
                .addCode(initBody.build())
                .build()
        )

        // Generate ILuaStaticDecl companion objects
        classInstances.forEach { classInfo ->
            mainObject.addType(generateStaticDeclObject(classInfo))
        }

        val fileSpec = FileSpec.builder(packageName, group.className)
            .addImport("me.ddayo.aris", "LuaMultiReturn")
            .addImport("party.iroiro.luajava", "Lua")
            .addImport("party.iroiro.luajava", "LuaException")
            .addImport("me.ddayo.aris", "LuaEngine", "ILuaStaticDecl", "LuaMain", "LuaFunc")
            .addType(mainObject.build())
            .build()

        // Collect doc strings
        val docString = group.instances.map { inst ->
            inst.bindings.values.joinToString("\n\n") { overloadGroup ->
                overloadGroup.overloads.filter { !it.isDocHidden }
                    .joinToString("\n") { buildDocString(it) }
            }
        }.filter { it.isNotBlank() }.joinToString("\n\n")

        return GeneratedOutput(fileSpec, allLuaCode.toString(), docString)
    }

    // --- Static binding generation ---

    private fun generateStaticBindings(
        instance: ProviderInstance.StaticInstance,
        initBody: CodeBlock.Builder,
        luaCode: StringBuilder
    ) {
        instance.bindings.forEach { (key, overloadGroup) ->
            val library = key.library
            val name = key.luaName
            val librarySp = libraryFnCount.getOrPut(library) { 0 }

            // Kotlin: push functions into library table
            initBody.addStatement("lua.getGlobal(%S)", library)
            initBody.beginControlFlow("if(lua.isNoneOrNil(-1))")
            initBody.addStatement("lua.pop(1)")
            initBody.addStatement("lua.newTable()")
            initBody.endControlFlow()

            overloadGroup.overloads.forEachIndexed { index, binding ->
                val processed = processBinding(binding)
                initBody.add("%L\n", processed.ktLambdaCode)
                initBody.addStatement("lua.setField(-2, %S)", "${name}_kt${index + librarySp}")
            }

            initBody.addStatement("lua.setGlobal(%S)", library)

            // Lua: dispatcher function
            luaCode.appendLine("function $library.$name(...)")
            luaCode.appendLine("    local as_table = { ... }")
            luaCode.appendLine("    local table_size = #as_table")
            luaCode.appendLine("    local score = -1")
            luaCode.appendLine("    local sel_fn = function() error(\"No matching argument\") end")
            overloadGroup.overloads.forEachIndexed { index, binding ->
                val processed = processBinding(binding)
                luaCode.append(
                    buildLuaScoreCalc(
                        processed,
                        "$library.${name}_kt${index + librarySp}"
                    )
                )
            }
            luaCode.appendLine("    return sel_fn(...)")
            luaCode.appendLine("end")

            if (libraryFnCount[library] == librarySp)
                libraryFnCount[library] = librarySp + overloadGroup.overloads.size
        }
    }

    // --- Instance binding generation ---

    private fun generateInstanceBindings(
        instance: ProviderInstance.ClassInstance,
        initBody: CodeBlock.Builder,
        luaCode: StringBuilder
    ) {
        val cln = instance.qualifiedName.replace('.', '_')

        initBody.addStatement("lua.newTable()")

        instance.bindings.forEach { (key, overloadGroup) ->
            val name = key.luaName

            // Kotlin: push functions into class table
            overloadGroup.overloads.forEachIndexed { index, binding ->
                val processed = processBinding(binding)
                initBody.add("%L\n", processed.ktLambdaCode)
                initBody.addStatement("lua.setField(-2, %S)", "${name}_kt${index}")
            }

            // Lua: dispatcher function
            luaCode.appendLine("function aris_${cln}_$name(...)")
            luaCode.appendLine("    local as_table = { ... }")
            luaCode.appendLine("    local table_size = #as_table")
            luaCode.appendLine("    local score = -1")
            luaCode.appendLine("    local sel_fn = function() error(\"No matching argument\") end")
            overloadGroup.overloads.forEachIndexed { index, binding ->
                val processed = processBinding(binding)
                luaCode.append(
                    buildLuaScoreCalc(
                        processed,
                        "aris_$cln.${name}_kt${index}"
                    )
                )
            }
            luaCode.appendLine("    return sel_fn(...)")
            luaCode.appendLine("end")
        }

        initBody.addStatement("lua.setGlobal(%S)", "aris_$cln")
    }

    // --- Metatable generation ---

    private fun generateMetatable(classInfo: ProviderInstance.ClassInstance, initBody: CodeBlock.Builder) {
        val cln = classInfo.qualifiedName.replace('.', '_')

        initBody.addStatement("lua.newTable()")
        initBody.addStatement("lua.getGlobal(%S)", "aris__gc")
        initBody.addStatement("lua.setField(-2, %S)", "__gc")
        initBody.addStatement("lua.getGlobal(%S)", "aris__newindex")
        initBody.addStatement("lua.setField(-2, %S)", "__newindex")
        initBody.addStatement("lua.getGlobal(%S)", "aris__eq")
        initBody.addStatement("lua.setField(-2, %S)", "__eq")
        initBody.addStatement("lua.newTable()")

        // Bind all functions into __index table
        classInfo.bindings.keys.forEach { (_, name) ->
            initBody.addStatement("lua.getGlobal(%S)", "aris_${cln}_$name")
            initBody.addStatement("lua.setField(-2, %S)", name)
        }

        // Handle inheritance
        classInfo.inheritFrom?.let { parentFqn ->
            val parentMtVar = mtVarName(parentFqn)
            val qualifiedMtRef = if (classInfo.inheritProviderClass?.isNotBlank() == true)
                "${classInfo.inheritProviderClass}.$parentMtVar"
            else
                parentMtVar

            initBody.addStatement("lua.newTable()")
            initBody.addStatement("lua.refGet($qualifiedMtRef)")
            initBody.addStatement("lua.getField(-1, %S)", "__index")
            initBody.addStatement("lua.setField(-3, %S)", "__index")
            initBody.addStatement("lua.pop(1)")
            initBody.addStatement("lua.setMetatable(-2)")
        }

        initBody.addStatement("lua.setField(-2, %S)", "__index")
        initBody.addStatement("${mtVarName(cln)} = lua.ref()")
        initBody.addStatement("engine.inner[%S] = ${mtVarName(cln)}", "${mtVarName(cln)}")
    }

    // --- ILuaStaticDecl object generation ---

    private fun generateStaticDeclObject(classInfo: ProviderInstance.ClassInstance): TypeSpec {
        val cln = classInfo.qualifiedName.replace('.', '_')
        val mtKey = mtVarName(cln)

        return TypeSpec.objectBuilder("${classInfo.simpleName}_LuaGenerated")
            .addSuperinterface(ClassName("me.ddayo.aris", "ILuaStaticDecl"))
            .addFunction(
                FunSpec.builder("toLua")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("engine", ClassName("me.ddayo.aris", "LuaEngine"))
                    .addParameter("lua", ClassName("party.iroiro.luajava", "Lua"))
                    .addStatement("lua.refGet(engine.inner[%S]!!)", mtKey)
                    .addStatement("lua.setMetatable(-2)")
                    .build()
            )
            .build()
    }

    // --- Single binding processing ---

    private data class ProcessedBinding(
        val ktLambdaCode: String,
        val luaRequiredArgs: Int,
        val isCoroutine: Boolean
    )

    private val bindingCache = mutableMapOf<LuaBinding, ProcessedBinding>()

    private fun processBinding(binding: LuaBinding): ProcessedBinding {
        return bindingCache.getOrPut(binding) {
            when (binding) {
                is LuaBinding.FunctionBinding -> processFunction(binding)
                is LuaBinding.PropertyGetterBinding -> processPropertyGetter(binding)
                is LuaBinding.PropertySetterBinding -> processPropertySetter(binding)
            }
        }
    }

    private fun processFunction(binding: LuaBinding.FunctionBinding): ProcessedBinding {
        val fn = binding.declaration
        val returnResolved = fn.returnType?.resolve()?.starProjection()
        val returnName = returnResolved?.declaration?.qualifiedName?.asString()
        val isCoroutine = returnName == "me.ddayo.aris.CoroutineProvider.LuaCoroutineIntegration"

        val argFilters = ArgumentManager.argFilters
        val preBuilder = StringBuilder()
        val mainBuilder = StringBuilder()
        var postBuilder = StringBuilder()
        var svp = 1
        var sip = 1

        // Return value prefix
        if (returnResolved?.let { paramCache.unitResolved.isAssignableFrom(it) } == false)
            mainBuilder.append("val rt = ")

        // Instance method: cast `this` from lua stack
        binding.declaredClass?.let { cl ->
            mainBuilder.append("(")
            val result = processArg(argFilters, cl.asStarProjectedType(), null, preBuilder, mainBuilder, postBuilder, svp, sip)
            svp = result.svp; sip = result.sip; postBuilder = result.postBuilder
            mainBuilder.append(").${fn.simpleName.asString()}")
        } ?: run {
            mainBuilder.append(fn.qualifiedName!!.asString())
        }

        // Parameters
        mainBuilder.append("(")
        val params = fn.parameters.map { it to it.type.resolve() }
        params.forEachIndexed { index, (vp, type) ->
            if (index != 0) mainBuilder.append(", ")
            val result = processArg(argFilters, type, vp, preBuilder, mainBuilder, postBuilder, svp, sip)
            svp = result.svp; sip = result.sip; postBuilder = result.postBuilder
        }
        mainBuilder.append(")")

        val lambdaCode = buildKtLambda(preBuilder, mainBuilder, postBuilder, svp, returnResolved)
        return ProcessedBinding(lambdaCode, svp - 1, isCoroutine)
    }

    private fun processPropertyGetter(binding: LuaBinding.PropertyGetterBinding): ProcessedBinding {
        val prop = binding.declaration
        val returnResolved = prop.type.resolve().starProjection()

        val argFilters = ArgumentManager.argFilters
        val preBuilder = StringBuilder()
        val mainBuilder = StringBuilder()
        var postBuilder = StringBuilder()
        var svp = 1
        var sip = 1

        mainBuilder.append("val rt = ")

        binding.declaredClass?.let { cl ->
            mainBuilder.append("(")
            val result = processArg(argFilters, cl.asStarProjectedType(), null, preBuilder, mainBuilder, postBuilder, svp, sip)
            svp = result.svp; sip = result.sip; postBuilder = result.postBuilder
            mainBuilder.append(").${prop.simpleName.asString()}")
        } ?: run {
            mainBuilder.append(prop.qualifiedName!!.asString())
        }

        val lambdaCode = buildKtLambda(preBuilder, mainBuilder, postBuilder, svp, returnResolved)
        return ProcessedBinding(lambdaCode, svp - 1, false)
    }

    private fun processPropertySetter(binding: LuaBinding.PropertySetterBinding): ProcessedBinding {
        val prop = binding.declaration
        val typeResolved = prop.type.resolve()

        val argFilters = ArgumentManager.argFilters
        val preBuilder = StringBuilder()
        val mainBuilder = StringBuilder()
        var postBuilder = StringBuilder()
        var svp = 1
        var sip = 1

        binding.declaredClass?.let { cl ->
            mainBuilder.append("(")
            val result = processArg(argFilters, cl.asStarProjectedType(), null, preBuilder, mainBuilder, postBuilder, svp, sip)
            svp = result.svp; sip = result.sip; postBuilder = result.postBuilder
            mainBuilder.append(").${prop.simpleName.asString()}")
        } ?: run {
            mainBuilder.append(prop.qualifiedName!!.asString())
        }
        mainBuilder.append(" = ")
        val result = processArg(argFilters, typeResolved, null, preBuilder, mainBuilder, postBuilder, svp, sip)
        svp = result.svp; sip = result.sip; postBuilder = result.postBuilder

        val lambdaCode = buildKtLambda(preBuilder, mainBuilder, postBuilder, svp, paramCache.unitResolved)
        return ProcessedBinding(lambdaCode, svp - 1, false)
    }

    // --- Argument processing helper ---

    private data class ArgProcessResult(val svp: Int, val sip: Int, val postBuilder: StringBuilder)

    private fun processArg(
        argFilters: List<ArgumentManager.Argument>,
        type: KSType,
        vp: KSValueParameter?,
        preBuilder: StringBuilder,
        mainBuilder: StringBuilder,
        postBuilder: StringBuilder,
        svp: Int,
        sip: Int
    ): ArgProcessResult {
        val newPostBuilder = StringBuilder()
        val p = argFilters.first { it.isValid(type, vp) }
            .process(preBuilder, mainBuilder, newPostBuilder, svp, sip, vp, type.declaration as KSClassDeclaration)
        return ArgProcessResult(p.first, p.second, newPostBuilder.append(postBuilder))
    }

    // --- Kotlin lambda builder ---

    private fun buildKtLambda(
        preBuilder: StringBuilder,
        mainBuilder: StringBuilder,
        postBuilder: StringBuilder,
        svp: Int,
        returnResolved: KSType?
    ): String {
        val sb = StringBuilder()
        sb.appendLine("lua.push { lua ->")
        sb.appendLine(preBuilder)
        sb.appendLine(mainBuilder)
        sb.appendLine(postBuilder)

        if (svp > 1) sb.appendLine("lua.pop(${svp - 1})")

        val returnNullable = returnResolved?.nullability == Nullability.NULLABLE
        fun handleNullable(str: String) =
            if (returnNullable) "rt?.let { rt -> $str } ?: lua.pushNil()" else str

        returnResolved?.let {
            when {
                paramCache.unitResolved.isAssignableFrom(it) ->
                    sb.appendLine("return@push 0")

                paramCache.numberResolved.isAssignableFrom(it)
                        || paramCache.stringResolved.isAssignableFrom(it)
                        || paramCache.booleanResolved.isAssignableFrom(it) -> {
                    sb.appendLine(handleNullable("lua.push(rt)"))
                    sb.appendLine("return@push 1")
                }

                paramCache.staticDeclResolved.isAssignableFrom(it) -> {
                    sb.appendLine(handleNullable("lua.pushJavaObject(rt)\nrt.toLua(engine, lua)"))
                    sb.appendLine("return@push 1")
                }

                else -> sb.appendLine("return@push LuaMain.pushNoInline(lua, rt)")
            }
        } ?: run { sb.appendLine("return@push 0") }

        sb.appendLine("}")
        return sb.toString()
    }

    // --- Lua score calculation ---

    private fun buildLuaScoreCalc(processed: ProcessedBinding, fnName: String): String {
        val sb = StringBuilder()
        sb.appendLine("if table_size >= ${processed.luaRequiredArgs} then")
        sb.appendLine("    local task_score = ${processed.luaRequiredArgs}")
        sb.appendLine("    if task_score >= score then")
        sb.appendLine("        score = task_score")
        sb.appendLine("        sel_fn = function(...)")

        if (processed.isCoroutine) {
            sb.appendLine("local coroutine = $fnName(...) -- get LuaCoroutine instance")
            sb.appendLine("while true do")
            sb.appendLine("    local it = coroutine:next_iter()")
            sb.appendLine("    if it:is_break() then")
            sb.appendLine("        return it:value()")
            sb.appendLine("    end")
            sb.appendLine("    task_yield(function() return it:finished() end)")
            sb.appendLine("end")
        } else {
            sb.appendLine("return $fnName(...)")
        }

        sb.appendLine("        end")
        sb.appendLine("    end")
        sb.appendLine("end")
        return sb.toString()
    }

    // --- Doc string builder ---

    private fun getLuaFriendlyName(type: KSType): String {
        val argFilters = ArgumentManager.argFilters
        val result = mutableListOf<String>()
        try {
            if (type.declaration is KSTypeParameter) return "Generic"
            val arg = argFilters.first { it.isValid(type, null) }
            arg.resolveDocSignature(null, type.declaration as KSClassDeclaration, result)
        } catch (_: Exception) {
            result.add(type.declaration.simpleName.asString())
        }
        return result.joinToString(", ")
    }

    private fun buildDocString(binding: LuaBinding): String {
        val sb = StringBuilder()
        when (binding) {
            is LuaBinding.FunctionBinding -> {
                val fn = binding.declaration
                val returnResolved = fn.returnType?.resolve()?.starProjection()
                val docSignature = fn.parameters.joinToString(", ") {
                    "${it.name?.asString()}: ${getLuaFriendlyName(it.type.resolve())}"
                }

                if (binding.declaredClass == null)
                    sb.append("## ${binding.luaName}($docSignature)")
                else
                    sb.append("## ${binding.declaredClass.simpleName.asString()}:${binding.luaName}($docSignature)")

                if (returnResolved != null && !returnResolved.isAssignableFrom(paramCache.unitResolved))
                    sb.appendLine(" -> ${getLuaFriendlyName(returnResolved)}")
                else sb.appendLine()

                fn.docString?.let { doc ->
                    sb.appendLine("```")
                    sb.append(' ')
                    sb.appendLine(doc.trim())
                    sb.appendLine("```")
                }
            }
            is LuaBinding.PropertyGetterBinding -> {
                val returnResolved = binding.declaration.type.resolve().starProjection()
                if (binding.declaredClass == null)
                    sb.appendLine("## ${binding.luaName}() -> ${getLuaFriendlyName(returnResolved)}")
                else
                    sb.appendLine("## ${binding.declaredClass.simpleName.asString()}:${binding.luaName}() -> ${getLuaFriendlyName(returnResolved)}")

                binding.declaration.docString?.let { doc ->
                    sb.appendLine("```")
                    sb.append(' ')
                    sb.appendLine(doc.trim())
                    sb.appendLine("```")
                }
            }
            is LuaBinding.PropertySetterBinding -> {
                val typeResolved = binding.declaration.type.resolve()
                if (binding.declaredClass == null)
                    sb.appendLine("## ${binding.luaName}(new_value: ${getLuaFriendlyName(typeResolved)})")
                else
                    sb.appendLine("## ${binding.declaredClass.simpleName.asString()}:${binding.luaName}(new_value: ${getLuaFriendlyName(typeResolved)})")

                binding.declaration.docString?.let { doc ->
                    sb.appendLine("```")
                    sb.append(' ')
                    sb.appendLine(doc.trim())
                    sb.appendLine("```")
                }
            }
        }
        return sb.toString()
    }

    companion object {
        fun mtVarName(classNameOrFqn: String): String {
            val normalized = classNameOrFqn.replace('.', '_')
            return "aris_${normalized}_mt"
        }
    }
}

data class GeneratedOutput(
    val fileSpec: FileSpec,
    val luaCode: String,
    val docString: String
)
