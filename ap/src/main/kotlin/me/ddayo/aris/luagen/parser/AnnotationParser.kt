@file:OptIn(KspExperimental::class)

package me.ddayo.aris.luagen.parser

import com.google.devtools.ksp.*
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.*
import me.ddayo.aris.luagen.*
import me.ddayo.aris.luagen.model.*

class AnnotationParser(
    private val logger: KSPLogger,
    private val defaultClassName: String
) {
    // Global map: class FQN -> provider group name (for cross-provider inheritance)
    private val classToProviderGroup = mutableMapOf<String, String>()

    fun parse(resolver: Resolver): ParseResult {
        val groups = mutableMapOf<String, ProviderGroupModel>()

        // First pass: discover all @LuaProvider classes and map them to provider groups
        val byProvider = mutableMapOf<String, MutableList<KSClassDeclaration>>()
        resolver.getSymbolsWithAnnotation(LuaProvider::class.java.canonicalName)
            .mapNotNull { it as? KSClassDeclaration }
            .forEach { classDecl ->
                classDecl.getAnnotationsByType(LuaProvider::class).forEach { annot ->
                    val providerName = if (annot.className == "!") defaultClassName else annot.className
                    byProvider.getOrPut(providerName) { mutableListOf() }.add(classDecl)
                    classToProviderGroup[classDecl.qualifiedName!!.asString()] = providerName
                }
            }

        // Second pass: process each provider group with topological sorting
        byProvider.forEach { (providerName, classes) ->
            val group = groups.getOrPut(providerName) { ProviderGroupModel(providerName) }
            val sorter = Sorter()
            val inheritMap = mutableMapOf<String, String>()
            val staticInstance = ProviderInstance.StaticInstance().also { group.instances.add(it) }

            // Register all classes in the sorter
            classes.forEach { classDecl ->
                val fqn = classDecl.qualifiedName!!.asString()
                sorter.addInstance(fqn, sorter.SorterInstance {
                    processClassDeclaration(classDecl, group, staticInstance, inheritMap)
                })
            }

            // Resolve inheritance relationships
            classes.forEach { current ->
                val currentFqn = current.qualifiedName!!.asString()
                current.superTypes
                    .map { it.resolve().declaration }
                    .filterIsInstance<KSClassDeclaration>()
                    .forEach { parent ->
                        if (parent.isAnnotationPresent(LuaProvider::class)) {
                            val parentFqn = parent.qualifiedName!!.asString()
                            inheritMap[currentFqn] = parentFqn
                            logger.info("Inherit: $currentFqn -> $parentFqn")

                            // Only add sorter dependency if parent is in same provider group
                            if (sorter[parentFqn] != null) {
                                sorter.setParent(parentFqn, currentFqn)
                            }
                        }
                    }
            }

            sorter.process()
        }

        // Third pass: auto-detect cross-provider inheritance (same module and cross-module)
        groups.values.forEach { group ->
            group.instances.filterIsInstance<ProviderInstance.ClassInstance>().forEach { classInfo ->
                val parentFqn = classInfo.inheritFrom ?: return@forEach
                // Only auto-set if not already explicitly set via annotation
                if (classInfo.inheritProviderClass != null) return@forEach

                val parentProvider = classToProviderGroup[parentFqn]
                if (parentProvider != null && parentProvider != group.className) {
                    // Same-module cross-provider
                    classInfo.inheritProviderClass = parentProvider
                } else if (parentProvider == null) {
                    // Cross-module: parent is from a compiled dependency.
                    // Read its @LuaProvider annotation (BINARY retention) to get the generated class name.
                    val parentDecl = classInfo.declaration.superTypes
                        .map { it.resolve().declaration }
                        .filterIsInstance<KSClassDeclaration>()
                        .firstOrNull { it.qualifiedName?.asString() == parentFqn }

                    parentDecl?.getAnnotationsByType(LuaProvider::class)?.firstOrNull()?.let { annot ->
                        val parentGenClassName = if (annot.className == "!") defaultClassName else annot.className
                        if (parentGenClassName != group.className) {
                            classInfo.inheritProviderClass = parentGenClassName
                        }
                    }
                }
            }
        }

        val deferred = resolver.getSymbolsWithAnnotation(LuaProvider::class.java.canonicalName)
            .filter { !it.validate() }.toList()
        if (deferred.isNotEmpty()) {
            logger.warn("Some class not processed: ${deferred.joinToString { it.location.toString() }}")
        }

        return ParseResult(groups, deferred)
    }

    private fun processClassDeclaration(
        classDecl: KSClassDeclaration,
        group: ProviderGroupModel,
        staticInstance: ProviderInstance.StaticInstance,
        inheritMap: MutableMap<String, String>
    ) {
        logger.info("Processing: ${classDecl.qualifiedName?.asString()}")

        val isStatic = when (classDecl.classKind) {
            ClassKind.CLASS -> false
            ClassKind.OBJECT -> true
            else -> throw LuaBindingException(
                "Cannot process ${classDecl.qualifiedName?.asString()}: Only class or object supported"
            )
        }

        classDecl.getAnnotationsByType(LuaProvider::class).forEach { providerAnnot ->
            val defaultLibrary = providerAnnot.library

            val targetInstance: ProviderInstance = if (isStatic) staticInstance
            else {
                val fqn = classDecl.qualifiedName!!.asString()
                ProviderInstance.ClassInstance(
                    declaration = classDecl,
                    qualifiedName = fqn,
                    simpleName = classDecl.simpleName.asString()
                ).also { inst ->
                    group.instances.add(inst)
                    if (inheritMap.containsKey(fqn)) {
                        inst.inheritFrom = inheritMap[fqn]
                        // Use explicit annotation value if provided
                        if (providerAnnot.inherit.isNotBlank()) {
                            inst.inheritProviderClass = providerAnnot.inherit
                        }
                    }
                }
            }

            // Process @LuaFunction annotated functions
            classDecl.getDeclaredFunctions().forEach { fn ->
                fn.getAnnotationsByType(LuaFunction::class).firstOrNull()?.let { annot ->
                    val luaName = if (annot.name == "!") fn.simpleName.asString() else annot.name
                    val library = if (annot.library == "_G") defaultLibrary else annot.library
                    val key = BindingKey(library, luaName)

                    targetInstance.bindings
                        .getOrPut(key) { OverloadGroup(library, luaName) }
                        .overloads.add(
                            LuaBinding.FunctionBinding(
                                luaName = luaName,
                                declaredClass = if (isStatic) null else classDecl,
                                isDocHidden = !annot.exportDoc,
                                declaration = fn
                            )
                        )
                    group.sourceFiles.add(classDecl.containingFile!!)
                }
            }

            // Process @LuaProperty annotated properties
            classDecl.getDeclaredProperties().forEach { prop ->
                prop.getAnnotationsByType(LuaProperty::class).firstOrNull()?.let { annot ->
                    val propName = if (annot.name == "!") prop.simpleName.asString() else annot.name
                    val library = if (annot.library == "_G") defaultLibrary else annot.library

                    if (annot.exportPropertySetter && prop.isMutable) {
                        val setKey = BindingKey(library, "set_$propName")
                        targetInstance.bindings
                            .getOrPut(setKey) { OverloadGroup(library, "set_$propName") }
                            .overloads.add(
                                LuaBinding.PropertySetterBinding(
                                    luaName = "set_$propName",
                                    declaredClass = if (isStatic) null else classDecl,
                                    isDocHidden = !annot.exportDoc,
                                    declaration = prop
                                )
                            )
                    }

                    val getKey = BindingKey(library, "get_$propName")
                    targetInstance.bindings
                        .getOrPut(getKey) { OverloadGroup(library, "get_$propName") }
                        .overloads.add(
                            LuaBinding.PropertyGetterBinding(
                                luaName = "get_$propName",
                                declaredClass = if (isStatic) null else classDecl,
                                isDocHidden = !annot.exportDoc,
                                declaration = prop
                            )
                        )
                    group.sourceFiles.add(classDecl.containingFile!!)
                }
            }
        }
    }
}

data class ParseResult(
    val groups: Map<String, ProviderGroupModel>,
    val deferred: List<KSAnnotated>
)
