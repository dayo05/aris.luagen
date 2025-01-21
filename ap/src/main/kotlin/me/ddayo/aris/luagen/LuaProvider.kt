package me.ddayo.aris.luagen

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class LuaProvider(val className: String = "!", val inherit: String = "", val library: String = "_G")
