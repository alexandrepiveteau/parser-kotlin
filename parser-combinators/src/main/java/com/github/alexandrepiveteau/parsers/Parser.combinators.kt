/*
 * MIT License
 *
 * Copyright (c) 2018 Alexandre Piveteau
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.alexandrepiveteau.parsers

import com.github.alexandrepiveteau.functional.monads.*

/**
 * Returns the combination of two [Parser]s but ignores the value returned by the first one. Implemented as a
 * combination of an [and] and a [map].
 *
 * @param I The type of the input provided to the [Parser].
 * @param O1 The type of the output provided by the original [Parser].
 * @param O2 The type of the output provided by the new [Parser].
 * @param E The type of errors generated by the parser.
 *
 * @param other The instance of [Parser] that this instance is combined with.
 */
fun <I, O1, O2, E> Parser<I, O1, E>.after(other: Parser<I, O2, E>): Parser<I, O2, E> =
        and(other).map { (_, b) -> b }

/**
 * Returns the combinator of two [Parser]s with as generated output a [Pair] of the result produced by the first
 * [Parser], followed by the output produced by the second [Parser] immediately after.
 *
 * If either of the two [Parser]s fail, the first [Parser] to fail will return its [E] error.
 *
 * @param I The type of the input provided to the [Parser].
 * @param O1 The type of the output provided by the original [Parser].
 * @param O2 The type of the output provided by the new [Parser].
 * @param E The type of errors generated by the parser.
 *
 * @param other The instance of [Parser] that this instance is combined with.
 */
fun <I, O1, O2, E> Parser<I, O1, E>.and(other: Parser<I, O2, E>): Parser<I, Pair<O1, O2>, E> =
        Parser { input ->
            return@Parser when (val result1 = this.parse(input)) {
                is Either.Error -> eitherError(result1.error)
                is Either.Value -> {
                    return@Parser when (val result2 = other.parse(result1.value.second)) {
                        is Either.Error -> eitherError(result2.error)
                        is Either.Value -> eitherValue(result1.value.first to result2.value.first to result2.value.second)
                    }
                }
            }
        }

/**
 * Returns the combination of two [Parser]s but ignores the value returned by the second one. Implemented as a
 * combination of an [and] and a [map].
 *
 * @param I The type of the input provided to the [Parser].
 * @param O1 The type of the output provided by the original [Parser].
 * @param O2 The type of the output provided by the new [Parser].
 * @param E The type of errors generated by the parser.
 *
 * @param other The instance of [Parser] that this instance is combined with.
 */
fun <I, O1, O2, E> Parser<I, O1, E>.before(other: Parser<I, O2, E>): Parser<I, O1, E> =
        and(other).map { (a, _) -> a }

/**
 * Returns a [Parser] that will perform the same operation as a [map], but flattens the result.
 *
 * @param I The type of the input provided to the [Parser].
 * @param O1 The type of the output provided by the original [Parser].
 * @param O2 The type of the output provided by the new [Parser].
 * @param E The type of errors generated by the parser.
 */
fun <I, O1, O2, E> Parser<I, O1, E>.flatMap(f: (O1) -> Either<E, O2>): Parser<I, O2, E> =
        Parser { input ->
            return@Parser when (val result = this.parse(input)) {
                is Either.Error -> eitherError(result.error)
                is Either.Value -> {
                    return@Parser when (val mappedResult = f(result.value.first)) {
                        is Either.Error -> eitherError(mappedResult.error)
                        is Either.Value -> eitherValue(mappedResult.value to result.value.second)
                    }
                }
            }
        }

/**
 * Returns a [Parser] that will perform the same operation as a [or], but flattens the result.
 *
 * @param I The type of the input provided to the [Parser].
 * @param O The type of the output provided by the [Parser].
 * @param E The type of errors generated by the parser.
 */
fun <I, O, E> Parser<I, O, E>.flatOr(other: Parser<I, O, E>): Parser<I, O, E> =
        or(other).map { either ->
            return@map when (either) {
                is Either.Error -> either.error
                is Either.Value -> either.value
            }
        }


/**
 * Returns a new instance of [Parser] that will have a bijection relationship to its child parser. The original [Parser]
 * will be used underneath, and a bi-directional mapping of values will occur. This operator acts on input values.
 *
 * This is a bit the opposite of the [map] operator that acts on output values.
 *
 * @param I1 The type of the input of the original [Parser].
 * @param I2 The type of the input of the new [Parser].
 * @param O The type of the output provided by the parsers.
 * @param E The type of th errors generated by the parser.
 *
 * @param f The mapping function from [I2] to [I1].
 * @param g The mapping function from [I1] to [I2].
 */
fun <I1, I2, O, E> Parser<I1, O, E>.local(f: (I2) -> I1, g: (I1) -> I2): Parser<I2, O, E> =
        Parser { a: I2 -> parse(f(a)).toValue().map { (x, y) -> x to g(y) }.either }

/**
 * Returns a [Parser] that will try to compose the original [Parser] into a [Parser] of a never-ending sequence of
 * outputs produced by the original [Parser]. The implementation of this [Parser] should be stack-safe, as it avoids
 * using recursion.
 *
 * @param I The type of the input provided to the [Parser].
 * @param O The type of the output provided by the [Parser].
 * @param E The type of errors generated by the parser.
 */
fun <I, O, E> Parser<I, O, E>.loop(): Parser<I, List<O>, E> =
        Parser { input ->
            val elements = mutableListOf<O>()
            var remainder = input
            val out: Either<E, Pair<List<O>, I>>
            iterator@ while (true) {
                val result = this.parse(remainder)
                when (result) {
                    is Either.Error -> {
                        out = eitherValue(elements to remainder)
                        break@iterator
                    }
                    is Either.Value -> {
                        elements += result.value.first
                        remainder = result.value.second
                    }
                }
            }
            return@Parser out
        }

/**
 * Returns a [Parser] where the original output value is mapped on to produce another type of output value. If you
 * want to act on the input type of the [Parser], you should use [local] instead.
 *
 * @param I The type of the input provided to the [Parser].
 * @param O1 The type of the output provided by the original [Parser].
 * @param O2 The type of the output provided by the new [Parser].
 * @param E The type of errors generated by the parser.
 *
 * @param f The mapping function from [O1] to [O2].
 */
fun <I, O1, O2, E> Parser<I, O1, E>.map(f: (O1) -> O2): Parser<I, O2, E> =
        Parser { input -> parse(input).toValue().map { (o, r) -> f(o) to r }.either }

/**
 * Returns a new instance of [Parser] that will map the content to a [Maybe] instance. Would the original [Parser] fail,
 * the new [Parser] will return an empty [Maybe] instance. Would the [Parser] succeed, the resulting value will simply
 * be wrapped in a [Maybe] type.
 *
 * @param I The type of the input provided to the [Parser].
 * @param O The type of the output provided by the [Parser].
 * @param E The type of errors generated by the parser.
 */
fun <I, O, E> Parser<I, O, E>.optional(): Parser<I, Maybe<O>, E> =
        map { maybeOf(it) }
                .flatOr(Parser.succeed<I, E>().map { emptyMaybe<O>() })

/**
 * Returns a new instance of [Parser] that will combine two different instances of [Parser] with the same input type and
 * a potentially different output type. The first [Parser] instance will be tried, and if it fails, the second [Parser]
 * instance will be tried instead. If both fail, the error message will be created by the second [Parser] instance.
 *
 * @param I The type of the input provided to the [Parser].
 * @param O1 The type of the output provided by the original [Parser].
 * @param O2 The type of the output provided by the new [Parser].
 * @param E The type of errors generated by the parser.
 *
 * @param other The instance of [Parser] that this instance is combined with.
 */
fun <I, O1, O2, E> Parser<I, O1, E>.or(other: Parser<I, O2, E>): Parser<I, Either<O2, O1>, E> =
        Parser { input ->
            return@Parser when (val result1 = parse(input)) {
                is Either.Error -> {
                    return@Parser when (val result2 = other.parse(input)) {
                        is Either.Error -> eitherError(result2.error)
                        is Either.Value -> eitherValue(eitherError<O2, O1>(result2.value.first) to result2.value.second)
                    }
                }
                is Either.Value -> eitherValue(eitherValue<O2, O1>(result1.value.first) to result1.value.second)
            }
        }