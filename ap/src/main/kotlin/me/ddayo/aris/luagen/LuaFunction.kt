package me.ddayo.aris.luagen

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class LuaFunction(
    val name: String = "!",
    val exportDoc: Boolean = true,
    /*
    This parameter only applicable on global function
     */
    val library: String = "_G"
)

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
