package io.quarkiverse.kotest.runtime

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class QuarkusTestCoroutineContextElement(
    val runningApp: Any,
    val classLoader: ClassLoader,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<QuarkusTestCoroutineContextElement>
}

suspend fun quarkusApp(): Any =
    currentCoroutineContext()[QuarkusTestCoroutineContextElement]?.runningApp
        ?: error("No Quarkus application in coroutine context")
