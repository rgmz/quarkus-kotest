# Building a native Kotest–Quarkus CDI extension via Strategy 2

**The most viable architecture uses Kotest's `ConstructorExtension` and `PostInstantiationExtension` to inject Quarkus CDI beans into native Kotest spec instances, piggybacking on the `FacadeClassLoader` that Quarkus 3.22+ already provides via the JUnit Platform's `LauncherInterceptor` SPI.** Because Kotest's `KotestJunitPlatformTestEngine` runs under the same JUnit Platform launcher as Jupiter, the `FacadeClassLoader` is active during Kotest's discovery phase — meaning much of the classloader routing comes for free if specs are annotated `@QuarkusTest`. The extension's job narrows from "reimplement Quarkus's entire bootstrap pipeline" to "resolve CDI beans from the already-running container, inject them into spec instances, and manage request scopes around test execution." No production-ready Kotest–Quarkus integration exists today: `kotest-examples-quarkus` is archived and non-functional, and `quarkiverse/quarkus-kotest` does not exist.

---

## The target developer experience

With this extension, users write standard Kotest specs with per-spec opt-in:

```kotlin
@QuarkusTest
@ApplyExtension(QuarkusKotestExtension::class)
class GreetingServiceTest : FunSpec() {
    @Inject lateinit var service: GreetingService

    init {
        test("greeting works") {
            service.greet("world") shouldBe "Hello, world!"
        }
    }
}
```

All spec styles work: `FunSpec`, `StringSpec`, `BehaviorSpec`, `DescribeSpec`, `WordSpec`, etc. Isolation modes, coroutine scoping, nested specs, Kotest listeners, property testing — all function because the Kotest engine remains in control of execution. The extension injects Quarkus's container *into* Kotest's lifecycle, not the other way around.

---

## Extension registration: `@ApplyExtension` with `ConstructorExtension`

Kotest 6.0 fixed the limitation where `@ApplyExtension` could not activate `ConstructorExtension` instances (issue #4260, merged via PR #4262). The Kotest 6.0 release notes confirm `@ApplyExtension` now works with all extension types. The Spring extension docs show this as the recommended per-spec registration pattern: `@ApplyExtension(SpringExtension::class)`.

This is significant for the Quarkus integration. It means:

- **No `AbstractProjectConfig` required.** Users don't need a global config class. The extension activates per-spec, exactly where it's needed.
- **No `@AutoScan` dependency.** Kotest 6.0 disables classpath scanning by default for performance. `@ApplyExtension` sidesteps this entirely.
- **Clean opt-in semantics.** Only specs annotated with both `@QuarkusTest` and `@ApplyExtension(QuarkusKotestExtension::class)` trigger the Quarkus lifecycle. Plain Kotest specs are unaffected.

Users who prefer global registration can still use project config:

```kotlin
class ProjectConfig : AbstractProjectConfig() {
    override val extensions = listOf(QuarkusKotestExtension)
}
```

---

## The FacadeClassLoader works at the JUnit Platform level — and Kotest benefits

This is the key architectural insight that simplifies the entire design. The `FacadeClassLoader` introduced in Quarkus 3.22 hooks into the JUnit Platform via `LauncherInterceptor`, a Platform-level SPI. `LauncherInterceptor` intercepts creation of `Launcher` instances and calls to `Launcher.discover()` and `Launcher.execute()`, which dispatch to *all* registered `TestEngine` implementations — including Kotest's `KotestJunitPlatformTestEngine`. The interceptor sets a thread context classloader before any engine starts scanning.

Since Kotest runs as a JUnit Platform engine, the `FacadeClassLoader` is already sitting between the Surefire/Gradle launcher and Kotest's discovery phase. The TCCL is set before Kotest even begins class scanning. This means:

**What comes for free:** When Kotest's engine discovers a spec class annotated `@QuarkusTest`, the `FacadeClassLoader` recognizes the annotation and routes that class through `QuarkusClassLoader`. The spec class sees augmented bytecodes (Panache companions, CDI interceptor bindings, REST endpoint discovery, etc.) automatically. The Quarkus application is bootstrapped during discovery — `QuarkusBootstrap → CuratedApplication → AugmentAction → StartupAction` — and the `RunningQuarkusApplication` is available by the time Kotest begins instantiating specs.

**What still needs work:** The `FacadeClassLoader` handles classloading but *not* CDI injection. After the class is loaded through `QuarkusClassLoader`, Kotest's engine instantiates it via its own `ConstructorExtension` chain — it never calls `QuarkusTestExtension.beforeAll()` or any Jupiter extension callbacks. So you get the right classloader context but not injection, mock support, request scoping, or test resource management. That's what the Kotest extension provides.

**What needs validation via prototyping:** Whether the `FacadeClassLoader`'s routing logic handles classes whose *only* Quarkus annotation is `@QuarkusTest` (with no JUnit `@Test` methods). If the `FacadeClassLoader` uses a secondary filter that checks for JUnit-specific annotations, Kotest specs would fall through and load via the standard app classloader. This is the first thing to test in a spike. If it's a problem, a deployment-side `@BuildStep` could teach the `FacadeClassLoader` to also recognize Kotest `Spec` subclasses.

### Implications for the bootstrap design

This dramatically simplifies the extension compared to the "reimplement the bootstrap from scratch" approach:

| Concern | Without FacadeClassLoader piggybacking | With FacadeClassLoader piggybacking |
|---|---|---|
| Quarkus application bootstrap | Extension must replicate the full `doJavaStart()` pipeline in `BeforeProjectListener` | Already done by `FacadeClassLoader` during discovery |
| Classloader management | Extension must manually load specs through `QuarkusClassLoader`, configure parent-first artifacts, manage TCCL | `FacadeClassLoader` handles routing; spec classes already load in the right CL |
| Augmentation (bytecode transforms) | Extension must trigger augmentation explicitly | Already complete before any spec is instantiated |
| Dev services startup | Extension must manage dev services lifecycle | Already started during discovery phase |
| CDI injection | Extension must resolve beans and inject fields | **Still needed** — this is the extension's core job |
| Request scope management | Extension must activate/deactivate per test | **Still needed** |
| Mock support (`@InjectMock`) | Extension must install mocks via `QuarkusMock` | **Still needed** |

The extension's scope reduces to: **obtain a reference to the already-running `RunningQuarkusApplication`, use it for CDI resolution and injection, and manage per-test scoping.**

---

## Kotest's extension points and the spec instantiation sequence

Understanding the exact lifecycle sequence is essential. Kotest's engine proceeds through these phases for every spec class, and each phase has a well-defined hook.

**Project bootstrap** fires first: all `BeforeProjectListener.beforeProject()` suspending functions execute once before any spec is instantiated. In the FacadeClassLoader-piggybacking model, Quarkus is already running by this point, so `beforeProject()` primarily needs to *locate* the running application rather than start it — by obtaining a reference to the `RunningQuarkusApplication` from wherever the `FacadeClassLoader` stores it (likely accessible via the TCCL or a static holder class loaded by `QuarkusClassLoader`).

**Spec instantiation** happens inside `createAndInitializeSpec()`. The engine folds through every registered `ConstructorExtension`, calling `fun <T : Spec> instantiate(clazz: KClass<T>): Spec?`. The first non-null result wins; if all return null, Kotest falls back to zero-arg constructor via reflection. Immediately after, all `PostInstantiationExtension` instances are folded: `suspend fun instantiated(spec: Spec): Spec`. Each receives the spec and returns a potentially modified instance. **Test registration happens during construction** — the `init` block or constructor lambda calls `test("...") { }`, which adds `TestCase` objects to the spec's internal registry. By the time `PostInstantiationExtension` runs, all tests are already registered.

**Spec execution** wraps in `SpecExtension.intercept(spec: Spec, execute: suspend (Spec) -> Unit)`, then per-test interception runs through `TestCaseExtension.intercept(testCase: TestCase, execute: suspend (TestCase) -> TestResult)`.

**IsolationMode matters for instance count.** Under `SingleInstance` (default), `ConstructorExtension` and `PostInstantiationExtension` fire once. Under `InstancePerRoot` (the replacement for the deprecated `InstancePerTest` and `InstancePerLeaf` in Kotest 6.0), they fire for every root test — meaning CDI injection must handle repeated invocations efficiently, reusing the running Quarkus application.

---

## How kotest-extensions-spring solves the identical problem

The Spring integration is the closest architectural analog and uses a **two-class design**:

**`SpringAutowireConstructorExtension`** implements `ConstructorExtension`. It checks if the spec's primary constructor has parameters; if not, it returns `null` and lets Kotest use the zero-arg constructor. If parameters exist, it calls `context.autowireCapableBeanFactory.autowire(clazz.java, AUTOWIRE_CONSTRUCTOR, true)`, creating a real instance by resolving constructor parameters from the Spring container. **This calls the real Kotlin constructor** — the init block runs, tests register, and constructor-parameter beans are available.

**`SpringTestExtension`** implements both `SpecExtension` and `TestCaseExtension`. In `SpecExtension.intercept()`, it creates a `TestContextManager`, calls `beforeTestClass()`, then `prepareTestInstance(spec)` — **this is where `@Autowired` field injection happens**, after the init block has already run. The `TestContextManager` is propagated through the **coroutine context** via a custom `AbstractCoroutineContextElement`, making it accessible to nested suspending calls. In `TestCaseExtension.intercept()`, it wraps each test with `beforeTestMethod()` / `afterTestMethod()` lifecycle callbacks.

The key patterns to replicate for Quarkus:

- **Constructor injection via `ConstructorExtension`**: Resolve params from ArC, call the real constructor
- **Field injection via `SpecExtension.intercept()` / `PostInstantiationExtension`**: Inject `@Inject lateinit var` fields before tests execute but after the init block
- **Per-test scoping via `TestCaseExtension`**: Activate/deactivate request scope
- **Context propagation via coroutine context element**: Make the `RunningQuarkusApplication` reference available throughout async test execution
- **Return null for non-applicable specs**: Gracefully ignore classes without `@QuarkusTest`

---

## The init-block problem is solvable, not a blocker

This was the most critical design question. Kotest's DSL-driven specs register tests during construction:

```kotlin
abstract class FunSpec(body: FunSpecRootScope.() -> Unit = {}) : DslDrivenSpec(), FunSpecRootScope {
    init { body() }  // Tests register HERE
}
```

If CDI (ArC) constructs the spec, three failure modes arise. For **`@ApplicationScoped`** specs, ArC creates a client proxy via a synthetic no-arg constructor — the init block runs but with null-valued injection points, producing a broken instance. For **`@Singleton`** specs, ArC creates the real instance but injects fields *after* construction, so `@Inject lateinit var` fields are null during the init block. Worst of all, **CDI cannot supply the DSL lambda** to `FunSpec({ ... })` — it has no way to provide a `FunSpecRootScope.() -> Unit` parameter.

The solution is to **never let CDI construct the spec**. Instead:

1. **Kotest constructs the spec normally** via its zero-arg constructor or via a `ConstructorExtension` that resolves constructor parameters from ArC (calling the real Kotlin constructor, not CDI's instantiation)
2. **`PostInstantiationExtension` injects `@Inject` fields** after construction, before test execution
3. Users reference `@Inject` fields only inside test body lambdas and lifecycle callbacks (not at init-block scope) — **identical to the constraint Spring users already face**

```kotlin
@QuarkusTest
@ApplyExtension(QuarkusKotestExtension::class)
class MySpec : FunSpec() {
    @Inject lateinit var service: GreetingService  // null during init, injected before tests run

    init {
        test("greeting works") {
            service.greet("world") shouldBe "Hello, world!"  // ✅ works: lambda executes after injection
        }
    }
}
```

For constructor injection (services as constructor parameters), the `ConstructorExtension` resolves each parameter from `Arc.container().select()` and calls the primary constructor directly — the init block runs with injected services available:

```kotlin
override fun <T : Spec> instantiate(clazz: KClass<T>): Spec? {
    if (clazz.findAnnotation<QuarkusTest>() == null) return null
    val constructor = clazz.primaryConstructor ?: return null
    if (constructor.parameters.isEmpty()) return null
    val args = constructor.parameters.map { param ->
        arcContainer.select(param.type.javaType as Class<*>).get()
    }.toTypedArray()
    return constructor.call(*args)
}
```

Note that ArC does **not** support `BeanManager.createInjectionTarget()` (a CDI Full feature). Field injection must use manual field resolution: iterate declared fields, check for `@Inject`, and call `Arc.container().select(field.type).get()`.

---

## Obtaining the running Quarkus application reference

Since the `FacadeClassLoader` has already bootstrapped Quarkus by the time Kotest instantiates specs, the extension needs to *find* the running application rather than *create* it. There are two approaches:

**Approach A — Access via QuarkusClassLoader context.** The `RunningQuarkusApplication` is stored in a static field accessible from the Quarkus classloader. The extension can reflectively access it:

```kotlin
val quarkusCL = Thread.currentThread().contextClassLoader
// The FacadeClassLoader sets TCCL to QuarkusClassLoader during execution
val holderClass = quarkusCL.loadClass("io.quarkus.test.junit.AbstractJvmQuarkusTestExtension")
val runningAppField = holderClass.getDeclaredField("runningQuarkusApplication")
runningAppField.isAccessible = true
val runningApp = runningAppField.get(null) as? RunningQuarkusApplication
```

**Approach B — Bootstrap if needed, reuse if available.** Use a `BeforeProjectListener` that checks whether a `RunningQuarkusApplication` exists (from `FacadeClassLoader`'s work) and only runs the full bootstrap if one doesn't. This provides a fallback for cases where the `FacadeClassLoader` doesn't activate (e.g., if its routing logic doesn't recognize the spec class):

```kotlin
override suspend fun beforeProject() {
    runningApp = findExistingQuarkusApp()
        ?: bootstrapQuarkusManually()  // Fallback: full bootstrap pipeline
}
```

Approach B is more robust and handles edge cases where the `FacadeClassLoader` piggybacking doesn't work as expected.

---

## Complete extension architecture

The full extension requires **four cooperating components**:

**`QuarkusKotestExtension`** — a composite `object` implementing all required interfaces. Registered via `@ApplyExtension(QuarkusKotestExtension::class)` per spec or globally via `AbstractProjectConfig`. This is the single entry point users interact with:

```kotlin
object QuarkusKotestExtension :
    ConstructorExtension,
    PostInstantiationExtension,
    SpecExtension,
    TestCaseExtension,
    BeforeProjectListener,
    AfterProjectListener {

    private var runningApp: RunningQuarkusApplication? = null

    override suspend fun beforeProject() {
        // Locate existing RunningQuarkusApplication from FacadeClassLoader
        // or bootstrap manually as fallback
    }

    override fun <T : Spec> instantiate(clazz: KClass<T>): Spec? {
        // For @QuarkusTest specs with constructor params:
        //   resolve params from Arc, call real constructor
        // For specs with no-arg constructor: return null (let Kotest handle it)
    }

    override suspend fun instantiated(spec: Spec): Spec {
        // Inject @Inject fields from Arc container
        // Inject @TestHTTPResource fields
        // Install @InjectMock mocks via QuarkusMock
        return spec
    }

    override suspend fun intercept(spec: Spec, execute: suspend (Spec) -> Unit) {
        // Set TCCL to QuarkusClassLoader around spec execution
        // Invoke QuarkusTestBeforeClassCallback SPI implementations
        val original = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = runningApp!!.classLoader
        try { execute(spec) }
        finally { Thread.currentThread().contextClassLoader = original }
    }

    override suspend fun intercept(
        testCase: TestCase,
        execute: suspend (TestCase) -> TestResult
    ): TestResult {
        // Activate request scope
        // Push mock context
        val reqCtx = Arc.container().requestContext()
        reqCtx.activate()
        try { return execute(testCase) }
        finally { reqCtx.terminate() }
    }

    override suspend fun afterProject() {
        // Only close if we bootstrapped manually (not if FacadeClassLoader owns lifecycle)
    }
}
```

A **`QuarkusCoroutineContextElement`** stores the `RunningQuarkusApplication` reference and ArC container in the coroutine context, making them accessible to test bodies via `coroutineContext[QuarkusCoroutineContextElement]`. This mirrors Spring's `SpringTestContextCoroutineContextElement`.

---

## CDI injection mechanics

**Field injection (`@Inject`):** Walk the spec's class hierarchy, find fields annotated with `jakarta.inject.Inject`, resolve each from ArC:

```kotlin
spec::class.java.declaredFields
    .filter { it.isAnnotationPresent(Inject::class.java) }
    .forEach { field ->
        field.isAccessible = true
        val bean = Arc.container().select(field.type).get()
        field.set(spec, bean)
    }
```

**`@InjectMock` support:** Create Mockito mocks, then install them:

```kotlin
spec::class.java.declaredFields
    .filter { it.isAnnotationPresent(InjectMock::class.java) }
    .forEach { field ->
        field.isAccessible = true
        val mock = Mockito.mock(field.type)
        QuarkusMock.installMockForType(mock, field.type)
        field.set(spec, mock)
    }
```

**`@TestHTTPResource`:** Resolve via `TestHTTPResourceManager`, loaded through the Quarkus classloader.

**`@TestHTTPEndpoint`:** Resolve the endpoint's URL path from the running application's REST resource metadata.

---

## Replicating test profiles and resource management

**`QuarkusTestProfile` support** requires re-bootstrapping Quarkus when the profile changes between spec classes. The extension must track the current profile and, if a new spec declares a different `@TestProfile`, shut down the running application and re-bootstrap with the new profile's config overrides, enabled alternatives, and command-line parameters. **If using FacadeClassLoader piggybacking**, the FacadeClassLoader may handle this automatically — it creates a new classloader per unique profile/resource combination during discovery. The extension would detect the profile change by checking the spec's classloader identity.

**`@QuarkusTestResource` / `TestResourceManager`** can be instantiated directly from `test-framework/common`. Call `manager.start()` for config properties and `manager.inject(testInstance)` to populate `@QuarkusTestResource`-annotated fields.

---

## Remaining risks and open questions

**FacadeClassLoader routing validation.** The most important unknown: does the `FacadeClassLoader` recognize `@QuarkusTest` on classes that have *no* JUnit `@Test` methods? Kotest specs register tests programmatically, not via annotations. If the `FacadeClassLoader` uses JUnit annotation presence as a secondary filter, specs would fall through. **This must be the first thing validated in a prototype.** If it fails, solutions include: a deployment-side `@BuildStep` to extend the `FacadeClassLoader`'s recognition logic, or falling back to manual bootstrap in `BeforeProjectListener`.

**Obtaining the `RunningQuarkusApplication` reference.** The `FacadeClassLoader` stores the running app in Quarkus's internal static state. The exact location and access pattern may not be a stable API. Reflective access to `AbstractJvmQuarkusTestExtension`'s fields is fragile. A more robust approach: use `Arc.container()` directly (loaded via TCCL) if the container is available, and derive the `RunningQuarkusApplication` from it.

**Dev mode (`quarkus:dev`) does not detect Kotest tests.** Quarkus's continuous testing infrastructure in `JunitTestRunner` hardcodes JUnit 5 test method discovery (`@Test`, `@ParameterizedTest`, `@TestFactory`, `@TestTemplate`, `@Testable`). Kotest specs are invisible to dev mode. Fixing this requires a Quarkus core change to make test discovery pluggable. Defer to a follow-up phase.

**ArC build-time bean discovery may not see test specs.** Quarkus discovers CDI beans at build time via Jandex indexing. Test classes must be in the application's Jandex index, or ArC won't recognize them as beans. Since we're *not* having ArC manage spec instances (we're using manual field injection via `Arc.container().select()`), this is not a blocker for field injection. It *would* matter if you wanted specs themselves to be CDI beans with interceptors, scopes, or producers.

**The parent-first classloader configuration** may still be needed if the `FacadeClassLoader` piggybacking doesn't fully resolve the class identity problem. If `io.kotest.*` classes are loaded by both the app classloader and `QuarkusClassLoader`, `ClassCastException` results. Configuring `io.kotest:*` as `quarkus.class-loading.parent-first-artifacts` ensures the Kotest framework types have a single class identity. This should be validated in the prototype alongside the `FacadeClassLoader` routing behavior.

---

## Implementation roadmap

**Phase 1 — Prototype and validate assumptions (1-2 weeks).** Build a minimal spike that answers three questions: (1) Does the `FacadeClassLoader` route `@QuarkusTest`-annotated Kotest specs through `QuarkusClassLoader`? (2) Can the extension obtain a `RunningQuarkusApplication` reference from the already-bootstrapped context? (3) Does `Arc.container().select()` work for field injection in Kotest-constructed spec instances? If (1) fails, fall back to manual bootstrap in `BeforeProjectListener`.

**Phase 2 — Core extension (2-3 weeks).** Implement the four components: `ConstructorExtension` for constructor-param injection, `PostInstantiationExtension` for field injection, `SpecExtension` for TCCL management, `TestCaseExtension` for request scope. Validate against all spec styles (`FunSpec`, `StringSpec`, `BehaviorSpec`, `DescribeSpec`, `WordSpec`). Validate isolation modes (`SingleInstance`, `InstancePerRoot`).

**Phase 3 — Test profiles, resources, mocking (1-2 weeks).** Add `@TestProfile` support (detect profile changes, trigger re-bootstrap). Add `@QuarkusTestResource` / `TestResourceManager` integration. Add `@InjectMock` / `QuarkusMock` support. Add `@TestHTTPResource` / `@TestHTTPEndpoint` support.

**Phase 4 — Packaging and Quarkiverse publishing (1 week).** Scaffold the Quarkus extension structure (deployment + runtime modules). The deployment module will be lightweight — primarily `FeatureBuildItem` registration and potentially a `@BuildStep` to extend `FacadeClassLoader` recognition if Phase 1 revealed it's needed. Publish to Quarkiverse with CI against Quarkus snapshots.

**Phase 5 (deferred) — Dev mode continuous testing.** Requires a Quarkus core change to make `JunitTestRunner` test discovery pluggable. File an issue / PR against Quarkus core. This is independent of the main extension and can be pursued in parallel.
