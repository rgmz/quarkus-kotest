package io.quarkiverse.kotest.it

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.quarkiverse.kotest.runtime.QuarkusKotestExtension
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject

@QuarkusTest
@ApplyExtension(QuarkusKotestExtension::class)
class KotestStringSpec : StringSpec() {

    @Inject
    lateinit var resource: KotestResource

    init {
        "CDI injection works in StringSpec" {
            resource.hello() shouldBe "Hello kotest"
        }
    }
}
