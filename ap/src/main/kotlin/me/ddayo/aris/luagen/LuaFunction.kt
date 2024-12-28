package me.ddayo.aris.luagen

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
/**
 * @param exportPropertySetter this only applied on property.
 */
annotation class LuaFunction(
    val name: String = "!",
    val exportDoc: Boolean = true,
    val exportPropertySetter: Boolean = true
)
