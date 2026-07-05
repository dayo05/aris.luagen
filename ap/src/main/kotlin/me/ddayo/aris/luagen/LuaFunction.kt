package me.ddayo.aris.luagen

import kotlin.reflect.KClass

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class LuaFunction(
    val name: String = "!",
    val exportDoc: Boolean = true,
    val callbacks: Array<LuaCallback> = [],
    /*
    This parameter only applicable on global function
     */
    val library: String = "_G"
)

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class LuaCallback(
    val param: String = "",
    val params: Array<LuaCallbackParam> = [],
    val returns: String = "nil"
)

@Target()
@Retention(AnnotationRetention.SOURCE)
annotation class LuaCallbackParam(
    val name: String,
    val type: KClass<*> = Any::class,
    val luaType: LuaType = LuaType.CUSTOM,
    val typeName: String = ""
)

enum class LuaType(val schemaName: String) {
    NIL("nil"),
    STRING("string"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    FUNCTION("function"),
    TABLE("table"),
    ANY("any"),
    CUSTOM("")
}

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class LuaProperty(
    val name: String = "!",
    val exportDoc: Boolean = true,
    val exportPropertySetter: Boolean = true,
    /*
    This parameter only applicable on global property
     */
    val library: String = "_G"
)
