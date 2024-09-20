package me.ddayo.aris

import party.iroiro.luajava.Lua

interface ILuaStaticDecl {
    fun toLua(lua: Lua)
}