package io.quarkiverse.kotest.it

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.quarkiverse.kotest.runtime.QuarkusKotestExtension
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject

/**
 * Tests injection of a plain CDI bean (not a REST resource or entity).
 * This catches the "unused bean removed" regression — if ArC doesn't see
 * the spec's @Inject field, GreetingService would be removed during build.
 */
@QuarkusTest
@ApplyExtension(QuarkusKotestExtension::class)
class CdiInjectionSpec : FunSpec() {

    @Inject
    lateinit var greetingService: GreetingService

    init {
        test("plain CDI bean injection works") {
            greetingService.greet("Kotest") shouldBe "Hello, Kotest!"
        }
    }
}
