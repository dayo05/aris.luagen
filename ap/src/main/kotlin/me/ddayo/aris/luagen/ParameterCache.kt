package me.ddayo.aris.luagen

import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSType

internal data class ParameterCache(
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
    val listResolved: KSType,
    val mapResolved: KSType,
    val classResolved: KSType,
    val unitResolved: KSType,
    val luaValueResolved: KSType,
    val coroutineResolved: KSType,
    val luaFuncResolved: KSType,
    val staticDeclResolved: KSType
) {
    companion object {
        fun init(resolver: Resolver) = ParameterCache(
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
            resolver.getClassDeclarationByName<List<*>>()!!.asStarProjectedType(),
            resolver.getClassDeclarationByName<Map<Any, Any>>()!!.asStarProjectedType(),
            resolver.getClassDeclarationByName<Class<*>>()!!.asStarProjectedType(),
            resolver.getClassDeclarationByName<Unit>()!!.asStarProjectedType(),
            resolver.getClassDeclarationByName("party.iroiro.luajava.value.LuaValue")!!.asStarProjectedType(),
            resolver.getClassDeclarationByName("me.ddayo.aris.CoroutineProvider.LuaCoroutineIntegration")!!
                .asStarProjectedType(),
            resolver.getClassDeclarationByName("me.ddayo.aris.LuaFunc")!!.asStarProjectedType(),
            resolver.getClassDeclarationByName("me.ddayo.aris.ILuaStaticDecl")!!.asStarProjectedType(),
        )
    }

    fun getLuaFriendlyName(decl: KSType): String {
        if(numberResolved.isAssignableFrom(decl))
            return "Number"
        if(stringResolved.isAssignableFrom(decl))
            return "String"
        if(booleanResolved.isAssignableFrom(decl))
            return "Boolean"
        if(luaFuncResolved.isAssignableFrom(decl))
            return "Function"
        return decl.declaration.simpleName.asString()
    }
}