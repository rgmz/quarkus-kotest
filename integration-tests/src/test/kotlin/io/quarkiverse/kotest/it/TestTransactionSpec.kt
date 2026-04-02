package io.quarkiverse.kotest.it

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.quarkiverse.kotest.runtime.QuarkusKotestExtension
import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest

@QuarkusTest
@TestTransaction
@ApplyExtension(QuarkusKotestExtension::class)
class TestTransactionSpec : FunSpec() {

    init {
        test("persist a greeting (will be rolled back)") {
            val greeting = Greeting()
            greeting.message = "Hello from test"
            greeting.persist()

            Greeting.count() shouldBe 1
        }

        test("database is clean after rollback") {
            Greeting.count() shouldBe 0
        }
    }
}
