package io.quarkiverse.kotest.runtime

import io.kotest.core.extensions.ConstructorExtension
import io.kotest.core.extensions.PostInstantiationExtension
import io.kotest.core.extensions.SpecExtension
import io.kotest.core.extensions.TestCaseExtension
import io.kotest.core.listeners.AfterProjectListener
import io.kotest.core.spec.Spec
import io.kotest.core.test.TestCase
import io.kotest.engine.test.TestResult
import kotlinx.coroutines.withContext
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaType

object QuarkusKotestExtension :
    ConstructorExtension,
    PostInstantiationExtension,
    SpecExtension,
    TestCaseExtension,
    AfterProjectListener {

    private const val QUARKUS_TEST_ANNOTATION = "io.quarkus.test.junit.QuarkusTest"

    @Volatile
    private var runningApp: Any? = null

    @Volatile
    private var appClassLoader: ClassLoader? = null

    @Volatile
    private var weOwnTheApp: Boolean = false

    private val lock = Any()

    // --- ConstructorExtension ---
    // Always create @QuarkusTest specs through QuarkusClassLoader so CDI beans
    // (from QuarkusClassLoader) are type-compatible with the spec's fields.
    // Requires Kotest framework classes to be parent-first so the returned
    // instance (from QuarkusClassLoader) is assignable to Spec (from parent).
    override fun <T : Spec> instantiate(clazz: KClass<T>): Spec? {
        if (!isQuarkusTest(clazz)) return null
        ensureStarted(clazz)
        val cl = appClassLoader ?: return null

        // Load the spec class through QuarkusClassLoader
        val quarkusClass = cl.loadClass(clazz.java.name)
        // Create instance via zero-arg constructor (all DSL specs have one)
        val instance = quarkusClass.getDeclaredConstructor().newInstance()
        System.err.println("[QuarkusKotest] instantiate() created spec@${System.identityHashCode(instance)} of ${quarkusClass.name} cl=${quarkusClass.classLoader?.javaClass?.name}")
        return instance as Spec
    }

    // --- PostInstantiationExtension ---
    // Inject @Inject fields into specs created through QuarkusClassLoader
    override suspend fun instantiated(spec: Spec): Spec {
        System.err.println("[QuarkusKotest] instantiated() called for ${spec::class.java.name}, cl=${spec::class.java.classLoader?.javaClass?.name}")
        if (!isQuarkusTest(spec::class)) {
            System.err.println("[QuarkusKotest] Not a @QuarkusTest, skipping injection")
            return spec
        }
        ensureStarted(spec::class)
        injectFields(spec)
        return spec
    }

    // --- SpecExtension ---
    override suspend fun intercept(spec: Spec, execute: suspend (Spec) -> Unit) {
        System.err.println("[QuarkusKotest] SpecExtension.intercept() spec@${System.identityHashCode(spec)} ${spec.javaClass.name}")
        // Check if field is still set
        try {
            val field = spec.javaClass.getDeclaredField("resource")
            field.isAccessible = true
            System.err.println("[QuarkusKotest]   resource field value: ${field.get(spec)}")
        } catch (_: Exception) {}

        val app = runningApp
        val cl = appClassLoader
        if (app == null || cl == null) {
            execute(spec)
            return
        }
        withContext(QuarkusTestCoroutineContextElement(app, cl)) {
            execute(spec)
        }
    }

    // --- TestCaseExtension ---
    override suspend fun intercept(
        testCase: TestCase,
        execute: suspend (TestCase) -> TestResult,
    ): TestResult {
        System.err.println("[QuarkusKotest] TestCaseExtension.intercept() test=${testCase.name.name} spec@${System.identityHashCode(testCase.spec)}")
        val cl = appClassLoader ?: return execute(testCase)
        val oldTccl = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = cl
        try {
            setupTestScope(cl)
            try {
                return execute(testCase)
            } finally {
                tearDownTestScope(cl)
            }
        } finally {
            Thread.currentThread().contextClassLoader = oldTccl
        }
    }

    // --- AfterProjectListener ---
    override suspend fun afterProject() {
        if (weOwnTheApp && runningApp != null) {
            try {
                runningApp!!.javaClass.getMethod("close").invoke(runningApp)
            } finally {
                runningApp = null
                appClassLoader = null
                weOwnTheApp = false
            }
        }
    }

    // --- Internal ---

    private fun isQuarkusTest(clazz: KClass<*>): Boolean {
        return clazz.java.annotations.any {
            it.annotationClass.qualifiedName == QUARKUS_TEST_ANNOTATION
        }
    }

    private fun ensureStarted(specClass: KClass<*>) {
        if (runningApp != null) return
        synchronized(lock) {
            if (runningApp != null) return

            try {
                // Kotest loads spec classes with its own classloader (AppClassLoader),
                // bypassing FacadeClassLoader. We must bridge to the QuarkusClassLoader
                // by going through FacadeClassLoader explicitly.
                val facadeLoader = getFacadeClassLoader()
                    ?: error(
                        "FacadeClassLoader not available. " +
                            "Ensure quarkus-junit is on the test classpath."
                    )

                System.err.println("[QuarkusKotest] FacadeClassLoader: ${facadeLoader.javaClass.name}")

                // Load the spec class through FacadeClassLoader, which routes
                // @QuarkusTest classes to QuarkusClassLoader (triggering augmentation)
                val quarkusLoadedClass = facadeLoader.loadClass(specClass.java.name)
                val quarkusCL = quarkusLoadedClass.classLoader

                System.err.println("[QuarkusKotest] Spec classloader: ${quarkusCL.javaClass.name}")

                // Check if Jupiter already started the app (e.g., a @QuarkusTest JUnit test ran first)
                val existingApp = findExistingRunningApp(quarkusCL)
                if (existingApp != null) {
                    System.err.println("[QuarkusKotest] Reusing existing RunningQuarkusApplication")
                    runningApp = existingApp
                    val getClassLoader = existingApp.javaClass.getMethod("getClassLoader")
                    appClassLoader = getClassLoader.invoke(existingApp) as ClassLoader
                    weOwnTheApp = false
                    return
                }

                // No running app — start it ourselves via StartupAction
                System.err.println("[QuarkusKotest] Starting Quarkus application...")
                val getStartupAction = try {
                    quarkusCL.javaClass.getMethod("getStartupAction")
                } catch (e: NoSuchMethodException) {
                    error(
                        "QuarkusClassLoader ${quarkusCL.javaClass.name} does not have getStartupAction(). " +
                            "FacadeClassLoader may not have routed the spec class correctly."
                    )
                }
                val startupAction = getStartupAction.invoke(quarkusCL)
                    ?: error("No StartupAction on QuarkusClassLoader")
                System.err.println("[QuarkusKotest] Got StartupAction: ${startupAction.javaClass.name}")
                val runMethod = startupAction.javaClass.getMethod("run", Array<String>::class.java)
                runningApp = runMethod.invoke(startupAction, arrayOf<String>())
                System.err.println("[QuarkusKotest] App started: ${runningApp!!.javaClass.name}")
                val getClassLoader = runningApp!!.javaClass.getMethod("getClassLoader")
                appClassLoader = getClassLoader.invoke(runningApp) as ClassLoader
                weOwnTheApp = true
            } catch (e: Exception) {
                System.err.println("[QuarkusKotest] ERROR in ensureStarted: ${e.javaClass.name}: ${e.message}")
                e.printStackTrace(System.err)
                throw e
            }
        }
    }

    /**
     * Access the FacadeClassLoader from CustomLauncherInterceptor's static field.
     * CustomLauncherInterceptor is loaded by the system classloader (it's in quarkus-junit).
     */
    private fun getFacadeClassLoader(): ClassLoader? {
        return try {
            val interceptorClass = Class.forName(
                "io.quarkus.test.junit.launcher.CustomLauncherInterceptor"
            )
            val facadeField = interceptorClass.getDeclaredField("facadeLoader")
            facadeField.isAccessible = true
            facadeField.get(null) as? ClassLoader
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if Jupiter's QuarkusTestExtension already started the app.
     * The RunningQuarkusApplication is stored as a static field on
     * AbstractJvmQuarkusTestExtension, loaded by QuarkusClassLoader.
     */
    private fun findExistingRunningApp(quarkusClassLoader: ClassLoader): Any? {
        return try {
            val extClass = quarkusClassLoader.loadClass(
                "io.quarkus.test.junit.AbstractJvmQuarkusTestExtension"
            )
            val appField = extClass.getDeclaredField("runningQuarkusApplication")
            appField.isAccessible = true
            appField.get(null)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get the ArC container and the InstanceHandle.get() method, using interface types
     * (not impl classes) to avoid cross-classloader IllegalAccessException.
     */
    private fun getArcContainer(cl: ClassLoader): Pair<Any, java.lang.reflect.Method> {
        val arcClass = cl.loadClass("io.quarkus.arc.Arc")
        val container = arcClass.getMethod("container").invoke(null)
            ?: error("ArC container not available")
        // Use the ArcContainer interface method, not the impl class method
        val arcContainerClass = cl.loadClass("io.quarkus.arc.ArcContainer")
        val instanceMethod = arcContainerClass.getMethod(
            "instance",
            Class::class.java,
            Array<Annotation>::class.java,
        )
        return container to instanceMethod
    }

    private fun getBeanFromHandle(handle: Any, cl: ClassLoader): Any {
        // Use the InstanceHandle interface to avoid IllegalAccessException on impl class
        val instanceHandleClass = cl.loadClass("io.quarkus.arc.InstanceHandle")
        val getMethod = instanceHandleClass.getMethod("get")
        return getMethod.invoke(handle) ?: error("InstanceHandle.get() returned null")
    }

    private fun resolveBean(type: Class<*>): Any {
        val cl = appClassLoader ?: error("Quarkus not started")
        val (container, instanceMethod) = getArcContainer(cl)
        val beanType = cl.loadClass(type.name)
        val handle = instanceMethod.invoke(container, beanType, emptyArray<Annotation>())
        return getBeanFromHandle(handle, cl)
    }

    private fun injectFields(spec: Spec) {
        val cl = appClassLoader ?: error("Quarkus not started")
        val (container, instanceMethod) = getArcContainer(cl)

        System.err.println("[QuarkusKotest] injectFields for ${spec.javaClass.name}, appCL=${cl.javaClass.name}")
        var clazz: Class<*>? = spec.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                val hasInject = field.annotations.any {
                    it.annotationClass.qualifiedName == "jakarta.inject.Inject"
                }
                System.err.println("[QuarkusKotest]   field: ${field.name} type=${field.type.name} hasInject=$hasInject annotations=${field.annotations.map { it.annotationClass.qualifiedName }}")
                if (hasInject) {
                    field.isAccessible = true
                    val beanType = cl.loadClass(field.type.name)
                    val handle = instanceMethod.invoke(container, beanType, emptyArray<Annotation>())
                    val bean = getBeanFromHandle(handle, cl)
                    System.err.println("[QuarkusKotest]   injecting ${bean.javaClass.name} into ${field.name} on spec@${System.identityHashCode(spec)}")
                    // Use Unsafe.putObjectVolatile for cross-thread visibility
                    val unsafeClass = Class.forName("sun.misc.Unsafe")
                    val theUnsafe = unsafeClass.getDeclaredField("theUnsafe")
                    theUnsafe.isAccessible = true
                    val unsafe = theUnsafe.get(null)
                    val offset = unsafeClass.getMethod("objectFieldOffset", java.lang.reflect.Field::class.java)
                        .invoke(unsafe, field) as Long
                    unsafeClass.getMethod("putObjectVolatile", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
                        .invoke(unsafe, spec, offset, bean)
                }
            }
            clazz = clazz.superclass
        }
    }

    private fun setupTestScope(quarkusClassLoader: ClassLoader) {
        val scopeManager = quarkusClassLoader.loadClass("io.quarkus.test.common.TestScopeManager")
        scopeManager.getMethod("setup", Boolean::class.javaPrimitiveType).invoke(null, false)
    }

    private fun tearDownTestScope(quarkusClassLoader: ClassLoader) {
        val scopeManager = quarkusClassLoader.loadClass("io.quarkus.test.common.TestScopeManager")
        scopeManager.getMethod("tearDown", Boolean::class.javaPrimitiveType).invoke(null, false)
    }
}