package me.ddayo.aris.luagen

import party.iroiro.luajava.Lua


interface ILuaStaticDecl {
    fun toLua(engine: LuaEngine, lua: Lua)
}