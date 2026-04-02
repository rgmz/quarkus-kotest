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

    @JvmStatic
    fun hasTestTransactionAnnotation(specClass: Class<*>): Boolean {
        return specClass.annotations.any {
            it.annotationClass.java.name == "io.quarkus.test.TestTransaction"
        }
    }

    @JvmStatic
    fun beginTransaction() {
        val tm = getTransactionManager()
        try {
            tm.javaClass.getMethod("begin").invoke(tm)
        } catch (e: Exception) {
            throw RuntimeException("Failed to begin transaction for @TestTransaction", e)
        }
    }

    @JvmStatic
    fun rollbackTransaction() {
        val tm = getTransactionManager()
        try {
            tm.javaClass.getMethod("rollback").invoke(tm)
        } catch (_: Exception) {
            // Transaction may already be rolled back or inactive
        }
    }

    private fun getTransactionManager(): Any {
        val tmClass = try {
            Class.forName("jakarta.transaction.TransactionManager", true, Thread.currentThread().contextClassLoader)
        } catch (e: ClassNotFoundException) {
            throw RuntimeException(
                "@TestTransaction requires a transaction manager. " +
                    "Add quarkus-narayana-jta (or another JTA provider) to your dependencies.",
                e
            )
        }
        return Arc.container().instance<Any>(tmClass).get()
            ?: throw RuntimeException(
                "TransactionManager CDI bean not available. " +
                    "Ensure quarkus-narayana-jta is on the classpath."
            )
    }
}
