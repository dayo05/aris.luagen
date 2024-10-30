package me.ddayo.aris.luagen

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import me.ddayo.aris.luagen.LuaFunctionProcessorProvider.Companion.parResolved

internal object ArgumentManager {
    object StringArgument : Argument {
        override fun resolve(
            index: Int,
            builder: StringBuilder,
            declaredClass: KSClassDeclaration,
            param: KSValueParameter?
        ): Int {
            super.resolve(index, builder, declaredClass, param)
            builder.append(".toString($index) ?: \"null\"")
            return 1
        }

        override fun isValid(type: KSType, param: KSValueParameter?) = parResolved.stringResolved.isAssignableFrom(type)
    }

    object LongArgument : Argument {
        override fun resolve(
            index: Int,
            builder: StringBuilder,
            declaredClass: KSClassDeclaration,
            param: KSValueParameter?
        ): Int {
            super.resolve(index, builder, declaredClass, param)
            builder.append(".toInteger($index)")
            return 1
        }

        override fun isValid(type: KSType, param: KSValueParameter?) = parResolved.longResolved.isAssignableFrom(type)
    }

    object IntArgument : Argument {
        override fun resolve(
            index: Int,
            builder: StringBuilder,
            declaredClass: KSClassDeclaration,
            param: KSValueParameter?
        ): Int {
            LongArgument.resolve(index, builder, declaredClass, param)
            builder.append(".toInt()")
            return 1
        }

        override fun isValid(type: KSType, param: KSValueParameter?) = parResolved.intResolved.isAssignableFrom(type)
    }

    object ShortArgument : Argument {
        override fun resolve(
            index: Int,
            builder: StringBuilder,
            declaredClass: KSClassDeclaration,
            param: KSValueParameter?
        ): Int {
            LongArgument.resolve(index, builder, declaredClass, param)
            builder.append(".toShort()")
            return 1
        }

        override fun isValid(type: KSType, param: KSValueParameter?) = parResolved.shortResolved.isAssignableFrom(type)
    }

    object ByteArgument : Argument {
        override fun resolve(
            index: Int,
            builder: StringBuilder,
            declaredClass: KSClassDeclaration,
            param: KSValueParameter?
        ): Int {
            LongArgument.resolve(index, builder, declaredClass, param)
            builder.append(".toByte()")
            return 1
        }

        override fun isValid(type: KSType, param: KSValueParameter?) = parResolved.byteResolved.isAssignableFrom(type)
    }

    object DoubleArgument : Argument {
        override fun resolve(
            index: Int,
            builder: StringBuilder,
            declaredClass: KSClassDeclaration,
            param: KSValueParameter?
        ): Int {
            super.resolve(index, builder, declaredClass, param)
            builder.append(".toNumber($index)")
            return 1
        }

        override fun isValid(type: KSType, param: KSValueParameter?) = parResolved.doubleResolved.isAssignableFrom(type)
    }

    object FloatArgument : Argument {
        override fun resolve(
            index: Int,
            builder: StringBuilder,
            declaredClass: KSClassDeclaration,
            param: KSValueParameter?
        ): Int {
            DoubleArgument.resolve(index, builder, declaredClass, param)
            builder.append(".toFloat()")
            return 1
        }

        override fun isValid(type: KSType, param: KSValueParameter?) = parResolved.floatResolved.isAssignableFrom(type)
    }

    object BooleanArgument: Argument {
        override fun resolve(
            index: Int,
            builder: StringBuilder,
            declaredClass: KSClassDeclaration,
            param: KSValueParameter?
        ): Int {
            super.resolve(index, builder, declaredClass, param)
            builder.append(".toBoolean($index)")
            return 1
        }

        override fun isValid(type: KSType, param: KSValueParameter?) = parResolved.booleanResolved.isAssignableFrom(type)
    }

    object LuaValueArgument : Argument {
        override fun isValid(type: KSType, param: KSValueParameter?) = parResolved.luaValueResolved.isAssignableFrom(type)
    }

    object EngineArgument : Argument {
        @OptIn(KspExperimental::class)
        override fun isValid(type: KSType, param: KSValueParameter?) = param?.getAnnotationsByType(LuaInstance::class)?.any() == true

        override fun resolve(
            index: Int,
            builder: StringBuilder,
            declaredClass: KSClassDeclaration,
            param: KSValueParameter?
        ): Int {
            builder.append("lua")
            return 0
        }
    }

    object JavaObjectArgument : Argument {
        override fun resolve(
            index: Int,
            builder: StringBuilder,
            declaredClass: KSClassDeclaration,
            param: KSValueParameter?
        ): Int {
            super.resolve(index, builder, declaredClass, param)
            builder.append(".toJavaObject($index) as ${intoProjectedStr(declaredClass)}")
            return 1
        }

        override fun isValid(type: KSType, param: KSValueParameter?) = true
    }

    interface Argument {
        fun resolve(index: Int, builder: StringBuilder, declaredClass: KSClassDeclaration, param: KSValueParameter?): Int {
            builder.append("lua")
            return 1
        }

        fun isValid(type: KSType, param: KSValueParameter?): Boolean
    }

    fun intoProjectedStr(classDecl: KSClassDeclaration): String {
        val s = StringBuilder(classDecl.qualifiedName!!.asString())
        if (classDecl.typeParameters.isNotEmpty()) (0 until classDecl.typeParameters.size).joinTo(
            s, prefix = "<", postfix = ">"
        ) { "*" }
        return s.toString()
    }

    val argFilters = listOf(
        StringArgument,
        LongArgument,
        IntArgument,
        ShortArgument,
        ByteArgument,
        DoubleArgument,
        FloatArgument,
        BooleanArgument,
        LuaValueArgument,
        EngineArgument,
        JavaObjectArgument
    )
}