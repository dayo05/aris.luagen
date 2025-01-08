package me.ddayo.aris.luagen

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class LuaFunction(
    val name: String = "!",
    val exportDoc: Boolean = true
)

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
/**
 * @param exportPropertySetter this only applied on property.
 */
annotation class LuaProperty(
    val name: String = "!",
    val exportDoc: Boolean = true,
    val exportPropertySetter: Boolean = true
)
