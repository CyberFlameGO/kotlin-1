// WITH_STDLIB
// IGNORE_BACKEND_FIR: JVM_IR

import kotlin.experimental.ExperimentalTypeInference

@OptIn(ExperimentalTypeInference::class)
fun <A, B> build(@BuilderInference block: BuilderScope<A>.() -> B): ResultProvider<A, B> = object : ResultProvider<A, B> {
    override fun provideResult(): A = "OK" as A
}

interface BuilderScope<A> {
    fun <B> getResult(result: ResultProvider<A, B>): B
}

interface ResultProvider<A, B> {
    fun provideResult(): A
}

val resultProvider: ResultProvider<Any, Any> = object : ResultProvider<Any, Any> {
    override fun provideResult(): Any = "NOK"
}

val result = build {
    getResult(build {
        getResult(resultProvider)
    })
}

fun box(): String {
    return result.provideResult().toString()
}
