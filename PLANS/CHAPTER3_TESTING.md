# Locally developing and testing a Quarkus-Kotest extension

**You can develop and fully test a Quarkus extension without publishing anything or cloning any upstream repositories.** The standard workflow uses `mvn install` to your local `~/.m2/repository`, depends on published `quarkus-bom` artifacts from Maven Central, and tests with two complementary strategies: `QuarkusUnitTest` in the deployment module for fast build-step validation, and a dedicated `integration-tests` module for end-to-end verification. For the specific challenge of testing a *test framework* extension—where you need tests that verify other tests work—Kotest's `TestEngineLauncher` with `CollectingTestEngineListener` provides a purpose-built programmatic execution API that both kotest-extensions-spring and Kotest's own codebase rely on heavily.

## The extension project structure and local build loop

A Quarkus extension is a multi-module project with three components: a **runtime** module (the artifact users add to their apps, containing CDI beans, recorders, and config), a **deployment** module (containing `@BuildStep` processors that execute during Quarkus's build-time augmentation), and an optional **integration-tests** module. The runtime module depends on `io.quarkus:quarkus-arc` (for CDI) and generates a descriptor file via `quarkus-extension-maven-plugin` that points to the deployment artifact. The deployment module depends on `io.quarkus:quarkus-arc-deployment` plus the sibling runtime module, and uses `@BuildStep`-annotated methods to produce build items like `FeatureBuildItem` and `AdditionalBeanBuildItem`.

**You do not need to clone the Quarkus or Kotest repositories.** The parent POM imports `io.quarkus:quarkus-bom` which manages all version-aligned Quarkus artifacts from Maven Central. Your extension depends on published APIs only—`quarkus-core`, `quarkus-arc`, `quarkus-core-deployment`, and `quarkus-arc-deployment` cover essentially everything needed. The Quarkus team explicitly designed the extension API surface (`@BuildStep`, `BuildItem`, `@Recorder`, `@ConfigMapping`) as the stable public contract for extension authors. Scaffold a new extension with:

```bash
mvn io.quarkus.platform:quarkus-maven-plugin:create-extension -N \
    -DgroupId=com.example -DextensionId=quarkus-kotest
```

The local development loop is straightforward: run `mvn clean install` in the extension project, which installs all three artifacts to `~/.m2/repository`. Any consuming Quarkus application—whether Maven or Gradle—resolves the extension from the local repo. For rapid iteration, `mvn install -DskipTests` shaves time. **Quarkus dev mode does not hot-reload external extensions**, so you must restart `quarkus:dev` after reinstalling the extension.

## Two testing strategies every extension needs

Quarkus extensions have two distinct testing concerns that map to different test modules and frameworks. Understanding this split is critical for designing your Kotest extension's test plan.

**Strategy 1: `QuarkusUnitTest` in the deployment module** validates that your build steps execute correctly. This uses `io.quarkus:quarkus-junit5-internal` (a test-scoped dependency in the deployment POM) and ShrinkWrap to construct synthetic applications on the fly. Each test declares a `static final QuarkusUnitTest` field with `@RegisterExtension`, configures a minimal application archive, then asserts on behavior:

```java
@RegisterExtension
static final QuarkusUnitTest test = new QuarkusUnitTest()
    .withApplicationRoot((jar) -> jar
        .addClasses(SampleKotestSpec.class)
        .addAsResource(new StringAsset("quarkus.kotest.enabled=true"),
            "application.properties"));

@Test
public void extensionRegistersKotestEngine() {
    // assert the extension's FeatureBuildItem appeared, beans registered, etc.
}
```

This approach is **fast and lightweight**—each test boots a minimal Quarkus app in-process with only your extension loaded. It's ideal for testing configuration variants, build-failure scenarios (via `.assertException()`), and verifying that specific build items are produced. The Quarkus scheduler extension, for instance, has dozens of these tests covering every cron expression variant.

**Strategy 2: `@QuarkusTest` in the integration-tests module** validates the full end-user experience. This module is a standalone Quarkus application that declares your extension's *runtime* artifact as a regular dependency (Quarkus auto-resolves the deployment artifact via the extension descriptor). Tests use `@QuarkusTest` from `io.quarkus:quarkus-junit5` and exercise the extension as a real user would. Following the Quarkiverse convention, this module is activated via a Maven profile rather than listed directly in `<modules>`:

```xml
<profiles>
    <profile>
        <id>it</id>
        <activation>
            <property><name>performRelease</name><value>!true</value></property>
        </activation>
        <modules><module>integration-tests</module></modules>
    </profile>
</profiles>
```

Every Quarkiverse extension—from quarkus-cucumber to quarkus-openapi-generator (which has **40+ integration test modules**)—follows this two-tier pattern. The Quarkus team summarizes it simply: `QuarkusUnitTest` tests the extension *itself*; `@QuarkusTest` tests *with* the extension.

## Meta-testing a test framework extension

Testing a test-framework integration poses a unique challenge: your extension's purpose is to make *other people's tests* work, so you need tests that verify tests. Three concrete patterns address this, ordered from simplest to most powerful.

**Pattern 1: Self-testing (circular).** Both `kotest-extensions-spring` and `micronaut-test-kotest5` use this approach. The test suite *itself* uses the extension being tested—if Spring/Micronaut injection fails, the tests simply can't run. For example, kotest-extensions-spring writes ordinary Kotest specs that `@Autowired` a service and assert on its behavior:

```kotlin
@ContextConfiguration(classes = [Components::class])
class SpringExtensionTest : WordSpec() {
    override fun extensions() = listOf(SpringExtension)
    @Autowired private lateinit var service: UserService
    init {
        "SpringExtension" should {
            "have autowired the service" {
                service.repository.findUser().name shouldBe "system_user"
            }
        }
    }
}
```

This works well for happy-path validation. For your Kotest-Quarkus extension, you'd write Kotest specs that use `@Inject` for Quarkus CDI beans and assert the beans function correctly. If your extension's CDI integration is broken, these specs fail immediately—either from `UninitializedPropertyAccessException` on `lateinit var` fields, or from constructor injection failures preventing spec instantiation.

**Pattern 2: Programmatic execution with `TestEngineLauncher`.** This is the most powerful approach and what Kotest's own codebase uses extensively for testing engine features. You write an outer test (a regular Kotest spec or JUnit test) that programmatically executes an inner spec via `TestEngineLauncher` and inspects the results through `CollectingTestEngineListener`:

```kotlin
// Inner spec: exercises your extension (private, not discovered by JUnit)
private class CdiInjectionSpec : FunSpec() {
    @Inject lateinit var greetingService: GreetingService
    init {
        test("CDI injection works") {
            greetingService.greet("World") shouldBe "Hello, World"
        }
    }
}

// Outer meta-test: runs the inner spec and asserts on outcomes
class QuarkusKotestExtensionMetaTest : FunSpec({
    test("extension should inject CDI beans into Kotest specs") {
        val listener = CollectingTestEngineListener()
        TestEngineLauncher(listener)
            .withClasses(CdiInjectionSpec::class)
            .launch()  // suspending function, native coroutine support
        listener.result("CDI injection works")?.isSuccess shouldBe true
    }

    test("extension should fail gracefully without @Inject") {
        val listener = CollectingTestEngineListener()
        TestEngineLauncher(listener)
            .withClasses(NoInjectionSpec::class)
            .launch()
        listener.result("should handle missing beans")?.isFailure shouldBe true
    }
})
```

The key API surface lives in `io.kotest:kotest-framework-engine`. `CollectingTestEngineListener` stores all test results in a `Map<TestCase, TestResult>`, and the `result(testName)` method retrieves individual outcomes. You can assert on `.isSuccess`, `.isFailure`, `.isError`, `.isIgnored`, `.errorOrNull` (to check exception types), and `.reasonOrNull` (for skip reasons). **Private inner spec classes** serve as test fixtures—they're invisible to the JUnit Platform launcher but executable programmatically.

**Pattern 3: JUnit Platform `EngineTestKit`.** If you need to test the extension from a JUnit perspective (verifying that Kotest specs appear correctly as JUnit Platform test descriptors), `org.junit.platform:junit-platform-testkit` provides `EngineTestKit`:

```java
EngineTestKit.engine("kotest")
    .selectors(selectClass(CdiInjectionSpec.class))
    .execute()
    .testEvents()
    .assertStatistics(stats -> stats.started(1).succeeded(1).failed(0));
```

This is complementary to `TestEngineLauncher`—use it when you need to verify JUnit Platform integration specifically.

## Verifying CDI injection actually worked

For a Quarkus-Kotest CDI extension, proving that injection happened is the core concern. A robust test plan uses a spectrum of verification approaches:

- **Behavioral verification** is strongest: call a method on the injected bean and assert on the result. `service.repository.findUser().name shouldBe "system_user"` proves the bean was injected, its dependencies were wired, and the full object graph functions. Both kotest-extensions-spring and micronaut-test-kotest5 rely primarily on this pattern.
- **Constructor injection** provides fail-fast verification: if the bean can't be resolved, the spec class can't be instantiated, producing an immediate and obvious error. Micronaut-test-kotest5 uses this exclusively: `class MathServiceTest(private val mathService: MathService) : BehaviorSpec({...})`.
- **Explicit null checks** with `shouldNotBeNull()` are useful for field/property injection where `lateinit var` might silently remain uninitialized in some edge cases.
- **`QuarkusUnitTest` with ShrinkWrap** lets you construct test applications containing specific bean configurations and verify that your deployment build steps correctly register those beans for injection.

## A concrete test plan for the Quarkus-Kotest extension

Combining all these patterns, here is a layered test plan for validating a Kotest-Quarkus CDI extension during development:

**Layer 1 — Deployment module unit tests** (`deployment/src/test/java/`). Use `QuarkusUnitTest` with `quarkus-junit5-internal` to verify build steps. Create synthetic applications via ShrinkWrap containing sample Kotest spec classes and assert that your `@BuildStep` methods produce the expected build items (`FeatureBuildItem`, `AdditionalBeanBuildItem`, any custom items for Kotest engine registration). Test configuration variants and expected build failures here.

**Layer 2 — Self-testing Kotest specs** (`integration-tests/src/test/kotlin/`). Write real Kotest specs that use your extension for CDI injection. These specs use `@Inject` on `lateinit var` fields or constructor parameters and assert on bean behavior. This validates the end-user experience directly. Include specs using different Kotest styles (FunSpec, BehaviorSpec, StringSpec) to verify your extension handles all spec types.

**Layer 3 — Meta-tests with `TestEngineLauncher`** (`integration-tests/src/test/kotlin/`). Write outer test classes that programmatically execute inner Kotest specs and inspect results via `CollectingTestEngineListener`. This layer tests edge cases that self-testing can't cover: specs where injection *should* fail, specs with multiple injection points, specs mixing injected and non-injected properties, and lifecycle ordering (does `@BeforeSpec` run after injection?). Private inner spec classes serve as fixtures.

**Layer 4 — JUnit Platform compatibility** (optional). Use `EngineTestKit` to verify that Kotest specs using your extension are correctly discovered and reported through the JUnit Platform, ensuring IDE and build-tool compatibility.

## Gradle vs Maven for the extension build

**Build your extension with Maven.** Every one of the 192+ Quarkiverse extensions uses Maven. The `quarkus create extension` CLI only generates Maven projects. The official guides document only Maven. While a `io.quarkus.extension` Gradle plugin exists, it is explicitly marked **experimental**, and the core team's GitHub issue for Gradle extension support (opened 2019) remains open with no milestone.

If your consuming *application* uses Gradle, the bridge is `mavenLocal()`. After `mvn install` on the extension, add `mavenLocal()` to the app's `repositories` block—but **list it after `mavenCentral()`** to avoid resolution failures for transitive dependencies. For SNAPSHOT versions, be aware that Gradle's `mavenLocal()` has known caching issues: once a SNAPSHOT is resolved from the local repo, Gradle may not detect updates even with `--refresh-dependencies`. The pragmatic workaround is `cacheChangingModulesFor(0, "seconds")` in your resolution strategy, or simply deleting the artifact from `~/.m2/repository` before rebuilding.

Gradle's `includeBuild` (composite builds) **cannot** reference Maven projects directly—it only works between Gradle projects. If you want to avoid the `mavenLocal()` boundary entirely, you'd need to port the extension build to Gradle using the experimental plugin and the community-documented multi-module setup from Quarkus GitHub Discussion #31999.

## Conclusion

The development workflow for a Quarkus-Kotest extension is well-supported by existing tooling and precedent—you need no upstream source clones, just published `quarkus-bom` artifacts and `mvn install` to iterate locally. The critical insight for testing a test-framework extension is the **two-layer meta-testing approach**: self-testing specs that use your extension directly (like kotest-extensions-spring does) validate the happy path, while `TestEngineLauncher` with `CollectingTestEngineListener` lets you programmatically execute specs and assert on outcomes including failures, skip reasons, and exception types. This combination—`QuarkusUnitTest` for build-step correctness, self-testing specs for end-user experience, and `TestEngineLauncher` for edge cases—provides comprehensive coverage without requiring any published artifacts or external infrastructure.
