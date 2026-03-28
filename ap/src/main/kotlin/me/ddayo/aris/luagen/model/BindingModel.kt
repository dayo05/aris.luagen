package me.ddayo.aris.luagen.model

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration

data class BindingKey(val library: String, val luaName: String)

sealed class LuaBinding {
    abstract val luaName: String
    abstract val declaredClass: KSClassDeclaration?
    abstract val isDocHidden: Boolean

    class FunctionBinding(
        override val luaName: String,
        override val declaredClass: KSClassDeclaration?,
        override val isDocHidden: Boolean,
        val declaration: KSFunctionDeclaration
    ) : LuaBinding()

    class PropertyGetterBinding(
        override val luaName: String,
        override val declaredClass: KSClassDeclaration?,
        override val isDocHidden: Boolean,
        val declaration: KSPropertyDeclaration
    ) : LuaBinding()

    class PropertySetterBinding(
        override val luaName: String,
        override val declaredClass: KSClassDeclaration?,
        override val isDocHidden: Boolean,
        val declaration: KSPropertyDeclaration
    ) : LuaBinding()
}

data class OverloadGroup(
    val library: String,
    val luaName: String,
    val overloads: MutableList<LuaBinding> = mutableListOf()
)

sealed class ProviderInstance {
    abstract val bindings: MutableMap<BindingKey, OverloadGroup>

    class StaticInstance(
        override val bindings: MutableMap<BindingKey, OverloadGroup> = mutableMapOf()
    ) : ProviderInstance()

    class ClassInstance(
        val declaration: KSClassDeclaration,
        val qualifiedName: String,
        val simpleName: String,
        override val bindings: MutableMap<BindingKey, OverloadGroup> = mutableMapOf(),
        var inheritFrom: String? = null,
        var inheritProviderClass: String? = null
    ) : ProviderInstance()
}

data class ProviderGroupModel(
    val className: String,
    val instances: MutableList<ProviderInstance> = mutableListOf(),
    val sourceFiles: MutableSet<KSFile> = mutableSetOf()
)
