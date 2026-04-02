package io.quarkiverse.kotest.runtime

import io.quarkus.arc.Arc
import io.quarkus.test.common.TestScopeManager

/**
 * Helper class loaded by QuarkusClassLoader. All Quarkus APIs (Arc, TestScopeManager)
 * are directly accessible here without reflection, because this class lives in the
 * runtime module and gets loaded by QuarkusClassLoader alongside the application.
 *
 * Called from [QuarkusKotestExtension] via a single reflective invocation across the
 * classloader boundary. The spec objects passed here are also from QuarkusClassLoader
 * (created in [QuarkusKotestExtension.instantiate]), so field types resolve correctly
 * against Arc's CDI container.
 */
object QuarkusKotestHelper {

    @JvmStatic
    fun injectFields(spec: Any) {
        val container = Arc.container()
        var clazz: Class<*>? = spec.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                if (field.annotations.any { it.annotationClass.java.name == "jakarta.inject.Inject" }) {
                    field.isAccessible = true
                    val handle = container.instance<Any>(field.type)
                    val bean = handle.get()
                        ?: throw RuntimeException(
                            "No CDI bean found for type ${field.type.name}" +
                                " (field '${field.name}' on ${spec.javaClass.name})"
                        )
                    field.set(spec, bean)
                }
            }
            clazz = clazz.superclass
        }
    }

    @JvmStatic
    fun setupTestScope() {
        TestScopeManager.setup(false)
    }

    @JvmStatic
    fun tearDownTestScope() {
        TestScopeManager.tearDown(false)
    }
}
