# Building a Quarkus extension for Kotest integration

**A Quarkus-Kotest extension is technically feasible but faces deep classloader coupling to JUnit 5 that makes a "pure" Kotest integration significantly harder than it appears.** The most viable path combines Kotest's `ConstructorExtension` and `PostInstantiationExtension` with Quarkus's CDI container, following the pattern established by the Spring and Micronaut Kotest integrations. No official Quarkus-Kotest extension exists despite years of community demand (Kotest issue #1401, open since April 2020; Quarkus issue #19749), and the archived `kotest-examples-quarkus` repo explicitly states "Quarkus support in Kotest is not yet complete." This report provides the concrete implementation blueprint.

---

## Scaffolding the extension project

The Quarkus CLI and Maven plugin both provide a `create-extension` command that generates the canonical multi-module layout. For a Quarkiverse extension targeting Kotest:

```bash
mvn io.quarkus.platform:quarkus-maven-plugin:3.31.0:create-extension -N \
    -DgroupId=io.quarkiverse.kotest \
    -DextensionId=kotest \
    -Dversion=1.0.0-SNAPSHOT \
    -Dquarkus.nameBase="Kotest"
```

The `-DgroupId=io.quarkiverse.*` prefix triggers the Quarkiverse layout auto-detection, producing this directory structure:

```
quarkus-kotest/
├── pom.xml                          # Parent POM (inherits quarkiverse-parent)
├── runtime/
│   ├── pom.xml
│   └── src/main/kotlin/io/quarkiverse/kotest/
│       ├── QuarkusKotestExtension.kt       # Kotest Extension composite
│       └── QuarkusSpec.kt                  # Marker annotation
├── deployment/
│   ├── pom.xml
│   └── src/main/java/io/quarkiverse/kotest/deployment/
│       └── KotestProcessor.java            # @BuildStep methods
├── integration-tests/
│   ├── pom.xml
│   └── src/test/kotlin/                    # Kotest specs that verify the extension
├── docs/modules/ROOT/
└── .github/project.yml                     # Quarkiverse release trigger
```

The parent POM must inherit from **`io.quarkiverse:quarkiverse-parent:20`** (released April 2025), which provides Maven Central deployment, GPG signing, CI validation, and release automation. The Quarkus BOM is imported for dependency management:

```xml
<parent>
    <groupId>io.quarkiverse</groupId>
    <artifactId>quarkiverse-parent</artifactId>
    <version>20</version>
</parent>
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-bom</artifactId>
            <version>${quarkus.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Two plugins are non-negotiable in every Quarkus extension. The runtime module needs `quarkus-extension-maven-plugin` to generate `META-INF/quarkus-extension.properties` (the file that links the runtime artifact to its deployment counterpart). Both modules need `quarkus-extension-processor` as a compiler annotation processor path to generate build-step metadata, config root lists, and Javadoc property files. Without these, Quarkus cannot discover or load the extension.

---

## How quarkus-junit5 works internally — and why it matters

Understanding the JUnit 5 test extension's internals is essential because any Kotest integration must either replicate, delegate to, or circumvent these mechanisms. The class hierarchy is **`AbstractTestWithCallbacksExtension` → `AbstractQuarkusTestWithContextExtension` → `AbstractJvmQuarkusTestExtension` → `QuarkusTestExtension`**, and the extension implements seven JUnit 5 interfaces: `BeforeAllCallback`, `BeforeEachCallback`, `AfterEachCallback`, `AfterAllCallback`, `BeforeTestExecutionCallback`, `AfterTestExecutionCallback`, `InvocationInterceptor`, and `ParameterResolver`.

The bootstrap sequence proceeds as follows. When `beforeAll()` fires, it calls `ensureStarted()`, which delegates to `doJavaStart()` if the Quarkus application isn't running or the test profile changed. `doJavaStart()` creates a `QuarkusBootstrap` → `CuratedApplication` → `AugmentAction` → `StartupAction` chain that executes all `@BuildStep` methods (the full Quarkus build pipeline) and starts the application. The running `RunningQuarkusApplication` instance is stored as a static singleton shared across test classes. Test instances are created via `runningQuarkusApplication.instance(testClass)`, which obtains a **full CDI bean** from the ArC container — meaning `@Inject` fields, interceptors, and scoped beans all work automatically.

**The critical change in Quarkus 3.22** (April 2025) is the `FacadeClassLoader`. Previously, tests were loaded by JUnit's classloader and then serialized/deserialized across the classloader boundary to the `QuarkusClassLoader` — a fragile approach that broke with Java 17+ module restrictions. Now, a `FacadeClassLoader` sits between the Surefire/IDE launcher and the application, routing class-loading requests directly to the `QuarkusClassLoader`. Tests load and run in the same classloader. **This is important for Kotest because it means Quarkus app startup now happens during the JUnit discovery phase**, not the execution phase.

The SPI callback interfaces (`QuarkusTestBeforeClassCallback`, `QuarkusTestAfterConstructCallback`, `QuarkusTestBeforeEachCallback`, `QuarkusTestAfterEachCallback`, `QuarkusTestAfterAllCallback`) are registered via `META-INF/services/` ServiceLoader files and invoked reflectively through the `QuarkusClassLoader`. These callbacks enable cross-cutting test concerns without touching the JUnit 5 extension itself.

---

## Three implementation strategies, ranked by viability

Research into existing non-JUnit test integrations reveals three distinct patterns. Each trades off complexity against native Kotest feel.

### Strategy 1: Piggyback on @QuarkusTest via @TestFactory (Cucumber pattern)

The `quarkiverse/quarkus-cucumber` extension — the most mature non-JUnit integration — **does not replace the Quarkus JUnit 5 infrastructure at all**. Instead, it creates an abstract base class annotated with `@QuarkusTest` containing a `@TestFactory` method that generates `DynamicTest` nodes from Cucumber features. All classloader management, CDI startup, injection, and test resource management are delegated to `QuarkusTestExtension`. A `CdiObjectFactory` bridges Cucumber's object creation to CDI by calling `CDI.current().select(type)` with careful thread context classloader management.

For Kotest, this would mean creating a base spec class that wraps Kotest execution inside a JUnit `@TestFactory`:

```kotlin
@QuarkusTest
abstract class QuarkusKotestBridge {
    @TestFactory
    fun kotestTests(): List<DynamicNode> {
        // Discover and execute Kotest specs inside the Quarkus context
        // Map Kotest results to JUnit DynamicTest/DynamicContainer
    }
}
```

**Pros**: Leverages all existing infrastructure — classloader, profiles, test resources, dev mode, mocking all work. **Cons**: Tests run through JUnit Platform, not the native Kotest engine. The Kotest DSL feel is lost, and users must extend a bridge class instead of writing idiomatic Kotest specs. This approach constrains rather than integrates Kotest.

### Strategy 2: Native Kotest extensions with Quarkus bootstrap (recommended)

This follows the pattern used by **kotest-extensions-spring** and the **Micronaut Kotest extension**. It uses Kotest's own extension points to control the lifecycle:

- **`ConstructorExtension`** — intercepts spec instantiation to load the class through `QuarkusClassLoader` and create the instance as a CDI bean
- **`PostInstantiationExtension`** — performs `@Inject` field injection via ArC after construction
- **`BeforeProjectListener` / `AfterProjectListener`** — starts and stops the Quarkus application once for the entire test suite
- **`TestCaseExtension`** — wraps individual test execution for transaction management and mock context

The composite extension object would look like:

```kotlin
@AutoScan
object QuarkusKotestExtension :
    ConstructorExtension,
    PostInstantiationExtension,
    BeforeProjectListener,
    AfterProjectListener,
    BeforeSpecListener,
    AfterSpecListener,
    TestCaseExtension {

    private lateinit var runningApp: RunningQuarkusApplication

    override suspend fun beforeProject() {
        // Bootstrap Quarkus: QuarkusBootstrap → CuratedApplication → StartupAction → run()
        // Store the RunningQuarkusApplication reference
    }

    override fun <T : Spec> instantiate(clazz: KClass<T>): Spec? {
        // Load class through QuarkusClassLoader
        // Return instance from Arc CDI container
        val quarkusCl = runningApp.classLoader
        val loadedClass = quarkusCl.loadClass(clazz.qualifiedName)
        return runningApp.instance(loadedClass) as Spec
    }

    override suspend fun instantiated(spec: Spec): Spec {
        // Additional field injection for @TestHTTPResource etc.
        return spec
    }

    override suspend fun intercept(
        testCase: TestCase,
        execute: suspend (TestCase) -> TestResult
    ): TestResult {
        // Push mock context, manage request scope
        MockSupport.pushContext()
        try { return execute(testCase) }
        finally { MockSupport.popContext() }
    }

    override suspend fun afterProject() {
        runningApp.close()
    }
}
```

**The key constraint**: `ConstructorExtension` is JVM-only and must be registered at the project level (via `ProjectConfig` or `@AutoScan`), not per-spec, because it runs before the spec instance exists. The `KClass` passed to `instantiate()` comes from the application classloader, so the extension must reload the class from `QuarkusClassLoader` and create the instance there.

**Pros**: Idiomatic Kotest. Users write standard `FunSpec`, `StringSpec`, etc. with `@Inject` fields. **Cons**: Must replicate the Quarkus bootstrap logic (`doJavaStart()` equivalent), handle classloader translation, and may break with internal Quarkus changes. Dev mode continuous testing will not work without additional work.

### Strategy 3: Lifecycle bridge (roggenbrot Spock approach)

This translates Kotest lifecycle events to the corresponding JUnit 5 interceptor calls on `QuarkusTestExtension`. It creates a Kotest extension that instantiates `QuarkusTestExtension` and calls its `beforeAll()`, `beforeEach()`, `interceptTestClassConstructor()`, etc. methods with synthesized JUnit 5 `ExtensionContext` objects.

**This is the most fragile approach.** Synthesizing fake `ExtensionContext` instances is error-prone, the internal APIs change between Quarkus versions, and the post-3.22 `FacadeClassLoader` integration assumes JUnit Platform discovery — making it even harder to bridge.

---

## The four hard problems for Kotest integration

### Classloader chicken-and-egg

Kotest's `KotestJunitPlatformTestEngine` discovers spec classes using the standard application classloader during JUnit Platform's discovery phase. Quarkus's `FacadeClassLoader` (3.22+) expects to intercept this discovery and route it through `QuarkusClassLoader`. **The Kotest engine will discover spec classes before Quarkus has a chance to intervene.** The `ConstructorExtension` solves this at instantiation time — it receives the original `KClass` and can reload the class from the Quarkus classloader — but this means two class objects exist for the same spec, which can cause `ClassCastException` issues if objects cross the classloader boundary.

The Cucumber extension sidesteps this entirely because it uses `@TestFactory` inside a `@QuarkusTest` class — the discovery runs through JUnit Jupiter's engine, not a foreign engine. For a native Kotest approach, the extension must ensure `Thread.currentThread().contextClassLoader` is set to the Quarkus classloader before CDI operations, following the pattern from `CdiObjectFactory`:

```kotlin
val oldCl = Thread.currentThread().contextClassLoader
try {
    Thread.currentThread().contextClassLoader = type.classLoader
    return CDI.current().select(type).get()
} finally {
    Thread.currentThread().contextClassLoader = oldCl
}
```

### CDI injection and mock support

CDI injection works when the spec instance is created as a bean from the ArC container. The extension should call `runningQuarkusApplication.instance(loadedClass)` to get a fully injected instance. For `@InjectMock` support, the extension must push/pop mock contexts around each test via `MockSupport.pushContext()` / `MockSupport.popContext()`, loaded reflectively from the Quarkus classloader. `QuarkusMock.installMockForType()` should work if the mock context is active.

### Continuous testing in dev mode

**This is the hardest problem.** Quarkus's `JunitTestRunner` (in `io.quarkus.deployment.dev.testing`) explicitly looks for JUnit annotations (`@Test`, `@RepeatedTest`, `@ParameterizedTest`, `@TestFactory`, `@TestTemplate`, `@Testable`) when discovering tests for continuous testing. Kotest specs use none of these annotations. Without patching `JunitTestRunner` to also recognize Kotest spec classes, **dev mode continuous testing will not discover Kotest specs**.

One workaround: a `@BuildStep` in the deployment module could register a `TestClassPredicateBuildItem` that recognizes Kotest `Spec` subclasses. However, this may not be sufficient because the test runner's annotation scanning happens at a different level. A full solution likely requires contributing a change to Quarkus core to make test discovery pluggable. This is a known gap — Quarkus issue #19749 specifically identifies this coupling.

### Test profile and resource management

`QuarkusTestProfile` and `@QuarkusTestResource` are processed by `TestResourceManager` during `QuarkusTestExtension.doJavaStart()`. In Strategy 2, the Kotest extension must replicate this: scan for `@QuarkusTestResource` annotations on spec classes, instantiate and start the `TestResourceManager`, and inject resource values. The `TestResourceManager` class is in `test-framework/common` and can be used directly if loaded through the Quarkus classloader.

---

## Deployment module build steps for a test extension

The deployment module needs minimal `@BuildStep` methods since most of the test integration logic lives in the runtime module. The essential build steps are:

```java
class KotestProcessor {
    private static final String FEATURE = "kotest";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    void registerKotestForReflection(BuildProducer<ReflectiveClassBuildItem> reflective) {
        // Register Kotest spec base classes and the extension for reflection
        // Required for native image support
        reflective.produce(ReflectiveClassBuildItem.builder(
            "io.quarkiverse.kotest.QuarkusKotestExtension"
        ).methods(true).fields(true).build());
    }

    @BuildStep
    void registerAdditionalBeans(BuildProducer<AdditionalBeanBuildItem> beans) {
        // Register any CDI beans the extension needs
    }
}
```

For a test framework extension, the deployment module is lightweight. The heavy lifting — classloader management, CDI injection, lifecycle orchestration — all belongs in the runtime module because it executes at test time, not build time. The deployment module's primary role is ensuring the extension's runtime classes are properly registered for reflection (essential for native image) and any CDI beans are discovered.

---

## Maven configuration specifics for Kotest

The runtime module POM needs Kotest dependencies alongside the Quarkus test infrastructure:

```xml
<dependencies>
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-arc</artifactId>
    </dependency>
    <dependency>
        <groupId>io.kotest</groupId>
        <artifactId>kotest-framework-api-jvm</artifactId>
        <version>${kotest.version}</version>
    </dependency>
    <dependency>
        <groupId>io.kotest</groupId>
        <artifactId>kotest-runner-junit5-jvm</artifactId>
        <version>${kotest.version}</version>
    </dependency>
</dependencies>
```

Since Kotest specs typically use `*Spec` naming rather than JUnit's `*Test` convention, the integration-test module's Surefire configuration must be extended:

```xml
<plugin>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <includes>
            <include>**/*Spec.*</include>
            <include>**/*Test.*</include>
        </includes>
        <systemPropertyVariables>
            <java.util.logging.manager>org.jboss.logmanager.LogManager</java.util.logging.manager>
        </systemPropertyVariables>
    </configuration>
</plugin>
```

Kotest runs on JUnit Platform via `KotestJunitPlatformTestEngine` (engine ID: `"kotest"`), registered through `META-INF/services/org.junit.platform.engine.TestEngine`. Surefire **3.5.4+** auto-detects JUnit Platform engines. One known issue: in multi-module reactor builds where Kotest and plain JUnit modules coexist, a `NoClassDefFoundError` for `LauncherFactory` can occur — the workaround is setting `<reuseForks>false</reuseForks>` in Surefire.

For Kotlin compilation in both runtime and deployment modules, add the `kotlin-maven-plugin` with `jvm-target` set to **17** (Quarkus 3.x minimum). The `maven-compiler-plugin` must include `quarkus-extension-processor` in annotation processor paths even when compiling Kotlin — configure `kapt` or use the processor in a mixed Java/Kotlin compilation setup.

---

## Quarkiverse publishing and ecosystem integration

The release process is automated through GitHub Actions. To release version **1.0.0**, a maintainer creates a PR changing `.github/project.yml`:

```yaml
release:
  current-version: "1.0.0"
  next-version: "1.0.1-SNAPSHOT"
```

Merging this PR triggers a workflow that executes `mvn release:prepare release:perform`, signs artifacts with GPG, and deploys to Maven Central via Sonatype Nexus. The PR **must** come from a branch in the origin repository, not a fork, because GitHub Actions secrets don't propagate to forks.

Before the first release, three registrations are needed. First, register the extension in the [quarkus-extension-catalog](https://github.com/quarkusio/quarkus-extension-catalog) for discoverability in Quarkus tooling. Second, register in [quarkus-ecosystem-ci](https://github.com/quarkusio/quarkus-ecosystem-ci) with an `info.yaml` file for automated testing against Quarkus snapshots. Third, register documentation in [quarkiverse-docs](https://github.com/quarkiverse/quarkiverse-docs) for the Antora-based documentation site.

Integration tests and docs modules must be excluded from the Maven Central release by using profile activation tied to `performRelease != true`.

---

## Conclusion: a recommended implementation roadmap

**Start with Strategy 2** (native Kotest extensions) because it produces the idiomatic developer experience Kotlin developers expect. The initial implementation should focus on four deliverables in order: (1) a `ConstructorExtension` that bootstraps Quarkus and creates spec instances via ArC CDI, (2) a `PostInstantiationExtension` for `@Inject` field injection and `@TestHTTPResource` handling, (3) `TestCaseExtension` for mock context management and request scope activation around each test, and (4) `BeforeProjectListener`/`AfterProjectListener` for application lifecycle.

Defer continuous testing support to a second phase — it requires changes to Quarkus core's `JunitTestRunner` to recognize non-JUnit-annotated test classes, which means either a Quarkus core PR or a creative workaround using `TestClassPredicateBuildItem`. The 3.22+ `FacadeClassLoader` rewrite may actually help here, since Quarkus now starts during JUnit Platform discovery rather than execution, meaning the Kotest engine's discovery phase could potentially run against an already-started Quarkus application if the classloader routing is configured correctly.

The biggest risk is classloader boundary crossings. Every interaction between Kotest's engine (loaded by the application classloader) and Quarkus's runtime (loaded by `QuarkusClassLoader`) must be carefully managed. The Cucumber extension's pattern of setting `Thread.currentThread().contextClassLoader` around every CDI operation is the proven mitigation. Build a robust test suite in the integration-tests module that exercises injection, mocking, scopes, and test resources to catch classloader issues early.
