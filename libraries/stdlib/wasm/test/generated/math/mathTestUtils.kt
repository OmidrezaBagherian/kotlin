/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package test.math

//
// NOTE: THIS FILE IS AUTO-GENERATED by the mathTestGeneratorMain.kt
// See: https://github.com/JetBrains/kotlin/tree/master/libraries/stdlib
//

import kotlin.math.*
import kotlin.test.*


            
//workaround about method with receiver
internal fun powWrapper(x: Double, y: Double): Double = x.pow(y)

private fun getMantissa(d: Double): Double = Double.fromBits(((d.toBits().toULong() and 0x800fffffffffffffUL) or 0x3ff0000000000000UL).toLong())
private fun getExp(d: Double): Int = (((d.toBits() shr 52) and 0x7FF) - 1023).toInt()

private fun compare(arg1: Double, arg2: Double?, result1: Double, result2: Double, exact: Boolean = false) {
    val difference: Any? = when {
        result1.isNaN() -> if (result2.isNaN()) null else result1
        result1.isInfinite() -> if (result2.isInfinite() && result1.sign == result2.sign) null else result1
        result2.isNaN() -> result2
        result2.isInfinite() -> result2
        else -> {
            if (exact) {
                if (result1.toBits() == result2.toBits()) null else abs(result1 - result2)
            } else {
                val (toCompare1, toCompare2) = when (getExp(result1) - getExp(result2)) {
                    0 -> getMantissa(result1) to getMantissa(result2)
                    1 -> getMantissa(result1) to getMantissa(result2) / 2.0
                    -1 -> getMantissa(result1) / 2.0 to getMantissa(result2)
                    else -> result1 to result2
                }
                abs(toCompare1 - toCompare2).takeIf { it > 1e-14 }
            }
        }
    }
    assertNull(difference, "ARG1 = " + arg1 + (if (arg2 == null) "" else " and ARG2 = " + arg2))
}

internal fun checkAnswers(function: Function1<Double, Double>, arguments: Array<ULong>, answers: Array<ULong>, exact: Boolean) {
    arguments.forEachIndexed { i, x ->
        val argument1 = Double.fromBits(x.toLong())
        val answer = Double.fromBits(answers[i].toLong())
        compare(argument1, null, answer, function(argument1), exact = exact)
    }
}

internal fun checkAnswers(function: Function2<Double, Double, Double>, arguments: Array<ULong>, answers: Array<ULong>, exact: Boolean) {
    arguments
        .flatMap { lhsElem -> arguments.map { rhsElem -> lhsElem to rhsElem } }
        .forEachIndexed { i, x ->
            val argument1 = Double.fromBits(x.first.toLong())
            val argument2 = Double.fromBits(x.second.toLong())
            val answer = Double.fromBits(answers[i].toLong())
            compare(argument1, argument2, answer, function(argument1, argument2), exact = exact)
        }
}