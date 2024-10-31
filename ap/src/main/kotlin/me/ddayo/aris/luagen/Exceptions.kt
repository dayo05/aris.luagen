package me.ddayo.aris.luagen

class LuaBindingException(message: String) : Exception(message)

open class SorterException(msg: String): Exception(msg)
class AlreadyFinalizedException: SorterException("Already sorted")
class SorterKeyExistsException: SorterException("Key already exists")
class ChainedDependencyException(msg: String): SorterException("Maybe chained dependency exists, left: $msg")
