package io.quarkiverse.kotest.it

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.quarkiverse.kotest.runtime.QuarkusKotestExtension
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.`is`

@QuarkusTest
@ApplyExtension(QuarkusKotestExtension::class)
class KotestResourceSpec : FunSpec() {

    @Inject
    lateinit var resource: KotestResource

    init {
        beforeSpec {
            RestAssured.port = Integer.getInteger("quarkus.http.test-port", 8081)
        }

        test("injected resource returns greeting") {
            resource.hello() shouldBe "Hello kotest"
        }

        test("REST endpoint responds") {
            given()
                .`when`().get("/kotest")
                .then()
                .statusCode(200)
                .body(`is`("Hello kotest"))
        }
    }
}
