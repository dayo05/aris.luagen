package me.ddayo.aris.luagen


open class SorterException(msg: String): Exception(msg)
class AlreadyFinalizedException: SorterException("Already sorted")
class SorterKeyExistsException: SorterException("Key already exists")
class ChainedDependencyException(msg: String): SorterException("Maybe chained dependency exists, left: $msg")

class Sorter {
    private val instances = mutableMapOf<String, SorterInstance>()
    fun addInstance(key: String, instance: SorterInstance) {
        if(instances.containsKey(key)) throw SorterKeyExistsException()
        instances[key] = instance
    }

    operator fun get(index: String) = instances[index]

    private val relation = mutableMapOf<String, MutableList<String>>()

    /**
     * ensure parent always processed first rather then child
     */
    fun setParent(parent: String, child: String) {
        relation.getOrPut(parent) { mutableListOf() }.add(child)
        instances[child]!!.ref()
    }

    private var finalized = false

    fun process() {
        if(finalized) throw AlreadyFinalizedException()
        finalized = true

        while(instances.isNotEmpty()) {
            val toRemove = mutableListOf<String>()
            instances.filterValues { it.leftReference == 0 }.forEach { (k, v) ->
                v.process()
                relation[k]?.forEach {
                    instances[it]?.unref()
                }
                toRemove.add(k)
            }
            if(toRemove.isEmpty())
                throw ChainedDependencyException(instances.entries.joinToString())
            toRemove.forEach {
                instances.remove(it)
            }
        }
    }

    inner class SorterInstance(val process: () -> Unit) {
        var leftReference = 0
            private set
        fun ref() { leftReference++ }
        fun unref() { leftReference-- }
        override fun toString(): String {
            return "left: $leftReference"
        }
    }
}