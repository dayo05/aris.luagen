package me.ddayo.aris.luagen

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import me.ddayo.aris.luagen.LuaFunctionProcessorProvider.Companion.parResolved

internal object ArgumentManager {
    class VarargArgument : Argument() {
        override fun resolve(
            index: Int,
            builder: StringBuilder,
            declaredClass: KSClassDeclaration,
            param: KSValueParameter?
        ): Int {
            TODO()
            return -1
        }

        override fun isValid(type: KSType, param: KSValueParameter?) = param?.isVararg == true
    }

    class StringArgument : Argument() {
        override fun resolve(
            index: Int,
            builder: StringBuilder,
            declaredClass: KSClassDeclaration,
            param: KSValueParameter?
        ): Int {
            builder.append("lua.toString($index) ?: \"null\"")
            return index + 1
        }

        override fun isValid(type: KSType, param: KSValueParameter?) = parResolved.stringResolved.isAssignableFrom(type)
    }

    open class LongArgument : Argument() {
        override fun resolve(
            index: Int,
            builder: StringBuilder,
            declaredClass: KSClassDeclaration,
            param: KSValueParameter?
        ): Int {
            builder.append("lua.toInteger($index)")
            return index + 1
        }

        override fun isValid(type: KSType, param: KSValueParameter?) = parResolved.longResolved.isAssignableFrom(type)
    }

    class IntArgument : LongArgument() {
        override fun resolve(
            index: Int,
            builder: StringBuilder,
            declaredClass: KSClassDeclaration,
            param: KSValueParameter?
        ): Int {
            super.resolve(index, builder, declaredClass, param)
            builder.append(".toInt()")
            return index + 1
        }

        override fun isValid(type: KSType, param: KSValueParameter?) = parResolved.intResolved.isAssignableFrom(type)
    }

    class ShortArgument : LongArgument() {
        override fun resolve(
            index: Int,
            builder: StringBuilder,
            declaredClass: KSClassDeclaration,
            param: KSValueParameter?
        ): Int {
            super.resolve(index, builder, declaredClass, param)
            builder.append(".toShort()")
            return index + 1
        }

        override fun isValid(type: KSType, param: KSValueParameter?) = parResolved.shortResolved.isAssignableFrom(type)
    }

    class ByteArgument : LongArgument() {
        override fun resolve(
            index: Int,
            builder: StringBuilder,
            declaredClass: KSClassDeclaration,
            param: KSValueParameter?
        ): Int {
            super.resolve(index, builder, declaredClass, param)
            builder.append(".toByte()")
            return index + 1
        }

        override fun isValid(type: KSType, param: KSValueParameter?) = parResolved.byteResolved.isAssignableFrom(type)
    }

    open class DoubleArgument : Argument() {
        override fun resolve(
            index: Int,
            builder: StringBuilder,
            declaredClass: KSClassDeclaration,
            param: KSValueParameter?
        ): Int {
            builder.append("lua.toNumber($index)")
            return index + 1
        }

        override fun isValid(type: KSType, param: KSValueParameter?) = parResolved.doubleResolved.isAssignableFrom(type)
    }

    class FloatArgument : DoubleArgument() {
        override fun resolve(
            index: Int,
            builder: StringBuilder,
            declaredClass: KSClassDeclaration,
            param: KSValueParameter?
        ): Int {
            super.resolve(index, builder, declaredClass, param)
            builder.append(".toFloat()")
            return index + 1
        }

        override fun isValid(type: KSType, param: KSValueParameter?) = parResolved.floatResolved.isAssignableFrom(type)
    }

    class BooleanArgument : Argument() {
        override fun resolve(
            index: Int,
            builder: StringBuilder,
            declaredClass: KSClassDeclaration,
            param: KSValueParameter?
        ): Int {
            builder.append("lua.toBoolean($index)")
            return index + 1
        }

        override fun isValid(type: KSType, param: KSValueParameter?) =
            parResolved.booleanResolved.isAssignableFrom(type)
    }

    class ListArgument : Argument() {
        override fun resolve(
            index: Int,
            builder: StringBuilder,
            declaredClass: KSClassDeclaration,
            param: KSValueParameter?
        ): Int {
            builder.append("lua.toList($index)")
            return index + 1
        }

        override fun isValid(type: KSType, param: KSValueParameter?) = parResolved.listResolved.isAssignableFrom(type)
    }

    class EngineArgument : Argument() {
        override fun resolve(
            index: Int,
            builder: StringBuilder,
            declaredClass: KSClassDeclaration,
            param: KSValueParameter?
        ): Int {
            builder.append("engine")
            return index
        }

        @OptIn(KspExperimental::class)
        override fun isValid(type: KSType, param: KSValueParameter?) =
            param?.getAnnotationsByType(RetrieveEngine::class)?.any() == true
    }

    class LuaFuncArgument : Argument() {
        override fun resolve(
            index: Int,
            builder: StringBuilder,
            declaredClass: KSClassDeclaration,
            param: KSValueParameter?
        ): Int {
            builder.append("LuaFunc(engine, $index)")
            return index + 1
        }

        override fun isValid(type: KSType, param: KSValueParameter?) =
            parResolved.luaFuncResolved.isAssignableFrom(type)
    }

    class JavaObjectArgument : Argument() {
        private var initStackPos = -1
        override fun init(builder: StringBuilder, currentStackPos: Int): Int {
            initStackPos = currentStackPos
            builder.appendLine("lua.refGet(LuaMain._luaGlobalMt)")
            return currentStackPos + 1
        }

        override fun preProcess(builder: StringBuilder, currentIndex: Int, currentStackPos: Int, param: KSValueParameter?): Int {
            builder.appendLine("lua.getMetatable($currentIndex)")
            builder.appendLine("lua.pushValue(-${currentStackPos - initStackPos + 1})")
            builder.appendLine("lua.setMetatable($currentIndex)")
            return currentStackPos + 1
        }

        override fun resolve(
            index: Int,
            builder: StringBuilder,
            declaredClass: KSClassDeclaration,
            param: KSValueParameter?
        ): Int {
            builder.append("lua.toJavaObject($index) as ${intoProjectedStr(declaredClass)}")
            return index + 1
        }

        override fun postProcess(
            builder: StringBuilder,
            currentIndex: Int,
            currentStackPos: Int,
            param: KSValueParameter?
        ): Int {
            builder.appendLine("lua.setMetatable($currentIndex)")
            return currentStackPos - 1
        }

        override fun finish(builder: StringBuilder, currentStackPos: Int): Int {
            builder.appendLine("lua.pop(1)")
            return currentStackPos - 1
        }

        override fun isValid(type: KSType, param: KSValueParameter?) = true
    }

    abstract class Argument {
        open fun init(builder: StringBuilder, currentStackPos: Int): Int {
            return currentStackPos
        }

        open fun preProcess(builder: StringBuilder, currentIndex: Int, currentStackPos: Int, param: KSValueParameter?): Int {
            return currentStackPos
        }

        abstract fun resolve(
            index: Int,
            builder: StringBuilder,
            declaredClass: KSClassDeclaration,
            param: KSValueParameter?
        ): Int

        open fun postProcess(builder: StringBuilder, currentIndex: Int, currentStackPos: Int, param: KSValueParameter?): Int {
            return currentStackPos
        }

        open fun finish(builder: StringBuilder, currentStackPos: Int): Int {
            return currentStackPos
        }

        private var isProcessed = false

        fun process(
            preBuilder: StringBuilder,
            mainBuilder: StringBuilder,
            postBuilder: StringBuilder,
            currentStackValuePos: Int,
            currentStackIncrementPos: Int,
            param: KSValueParameter?,
            declaredClass: KSClassDeclaration
        ): Pair<Int, Int> {
            var nsp = currentStackIncrementPos
            if (!isProcessed)
                nsp = init(preBuilder, nsp)
            nsp = preProcess(preBuilder, currentStackValuePos, nsp, param)
            val postProc = nsp
            val newIndex = resolve(currentStackValuePos, mainBuilder, declaredClass, param)
            nsp = postProcess(postBuilder, currentStackValuePos, nsp, param)
            if (!isProcessed)
                nsp = finish(postBuilder, nsp)
            if (nsp != currentStackIncrementPos)
                throw IllegalStateException("Stack Overflow Expected nsp: $nsp != stackPos: $currentStackIncrementPos at index $currentStackValuePos")
            isProcessed = true
            return newIndex to postProc
        }

        abstract fun isValid(type: KSType, param: KSValueParameter?): Boolean
    }

    fun intoProjectedStr(classDecl: KSClassDeclaration): String {
        val s = StringBuilder(classDecl.qualifiedName!!.asString())
        if (classDecl.typeParameters.isNotEmpty()) (0 until classDecl.typeParameters.size).joinTo(
            s, prefix = "<", postfix = ">"
        ) { "*" }
        return s.toString()
    }

    val argFilters
        get() = listOf(
            VarargArgument(),
            StringArgument(),
            LongArgument(),
            IntArgument(),
            ShortArgument(),
            ByteArgument(),
            DoubleArgument(),
            FloatArgument(),
            BooleanArgument(),
            ListArgument(),
            LuaFuncArgument(),
            EngineArgument(),
            JavaObjectArgument()
        )
}