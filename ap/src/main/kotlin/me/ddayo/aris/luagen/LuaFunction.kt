package me.ddayo.aris.luagen

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class LuaFunction(val name: String = "!", val exportDoc: Boolean = true)
