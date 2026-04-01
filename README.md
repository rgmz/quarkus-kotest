# Quarkus Kotest

A Quarkus extension that integrates the [Kotest](https://kotest.io/) testing framework with Quarkus CDI. Write native Kotest specs (`FunSpec`, `StringSpec`, `BehaviorSpec`, etc.) with `@Inject` fields that resolve from Quarkus's ArC container.

**Status**: Early prototype. CDI field injection works. See [Current Limitations](#current-limitations) below.

## Quick Start

```kotlin
@QuarkusTest
@ApplyExtension(QuarkusKotestExtension::class)
class GreetingServiceTest : FunSpec() {
    @Inject
    lateinit var service: GreetingService

    init {
        test("greeting works") {
            service.greet("world") shouldBe "Hello, world!"
        }
    }
}
```

## Building Locally

Requires Java 17+ and Maven 3.9+.

```bash
git clone <this-repo>
cd quarkus-kotest
mvn clean install -Dno-format
```

This installs the extension to your local `~/.m2/repository`.

## Using in Your Project

### 1. Add dependencies to your project's `pom.xml`

```xml
<properties>
    <kotest.version>6.0.1</kotest.version>
    <quarkus-kotest.version>1.0.0-SNAPSHOT</quarkus-kotest.version>
</properties>

<dependencies>
    <!-- Quarkus Kotest extension (runtime) -->
    <dependency>
        <groupId>io.quarkiverse.kotest</groupId>
        <artifactId>quarkus-kotest</artifactId>
        <version>${quarkus-kotest.version}</version>
    </dependency>

    <!-- Kotest runner + assertions (test scope) -->
    <dependency>
        <groupId>io.kotest</groupId>
        <artifactId>kotest-runner-junit5-jvm</artifactId>
        <version>${kotest.version}</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>io.kotest</groupId>
        <artifactId>kotest-assertions-core-jvm</artifactId>
        <version>${kotest.version}</version>
        <scope>test</scope>
    </dependency>

    <!-- Quarkus test framework -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-junit</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### 2. Add Kotlin compilation for tests

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-maven-plugin</artifactId>
            <version>${kotlin.version}</version>
            <executions>
                <execution>
                    <id>test-compile</id>
                    <goals><goal>test-compile</goal></goals>
                    <configuration>
                        <sourceDirs>
                            <sourceDir>${project.basedir}/src/test/kotlin</sourceDir>
                        </sourceDirs>
                    </configuration>
                </execution>
            </executions>
            <configuration>
                <jvmTarget>17</jvmTarget>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### 3. Configure parent-first classloading

Add to `src/main/resources/application.properties`:

```properties
quarkus.class-loading.parent-first-artifacts=io.kotest:kotest-framework-engine-jvm,io.kotest:kotest-runner-junit5-jvm,io.kotest:kotest-runner-junit-platform-jvm,io.kotest:kotest-common-jvm,io.kotest:kotest-assertions-core-jvm,io.kotest:kotest-assertions-shared-jvm,io.kotest:kotest-extensions-jvm,org.jetbrains.kotlin:kotlin-stdlib,org.jetbrains.kotlin:kotlin-reflect,org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm,org.jetbrains.kotlinx:kotlinx-coroutines-core
```

This is required because Kotest and Kotlin classes must be shared between the system classloader and Quarkus's classloader.

### 4. Configure Surefire

Ensure Surefire discovers `*Spec` files:

```xml
<plugin>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <includes>
            <include>**/*Test.*</include>
            <include>**/*Spec.*</include>
        </includes>
    </configuration>
</plugin>
```

### 5. Write your tests

Place Kotest specs in `src/test/kotlin/`:

```kotlin
@QuarkusTest
@ApplyExtension(QuarkusKotestExtension::class)
class MyServiceSpec : FunSpec() {
    @Inject
    lateinit var myService: MyService

    init {
        test("service does something") {
            myService.doSomething() shouldBe "expected"
        }
    }
}
```

For REST endpoint tests, configure RestAssured's port:

```kotlin
init {
    beforeSpec {
        RestAssured.port = Integer.getInteger("quarkus.http.test-port", 8081)
    }
    // ...
}
```

## How It Works

1. `@QuarkusTest` triggers Quarkus's `FacadeClassLoader` to route the spec class through `QuarkusClassLoader` (augmentation, CDI setup)
2. `@ApplyExtension(QuarkusKotestExtension::class)` registers the extension with Kotest
3. `ConstructorExtension` creates spec instances through `QuarkusClassLoader` so CDI types are compatible
4. `PostInstantiationExtension` injects `@Inject` fields from the ArC container
5. `TestCaseExtension` activates/deactivates CDI request scope per test
6. `AfterProjectListener` shuts down the Quarkus application when all specs complete

## Current Limitations

- **No `@InjectMock` / `QuarkusMock` support** (planned)
- **No `@TestProfile` support** (planned)
- **No `@QuarkusTestResource` support** (planned)
- **No `@TestHTTPResource` / `@TestHTTPEndpoint` support** (planned)
- **RestAssured port must be configured manually** (see above)
- **`@RequestScoped` beans**: CDI request scope is ThreadLocal-based; if test bodies dispatch to other threads via coroutine dispatchers, request-scoped beans won't be visible on those threads
- **`SpecExtension` and `TestCaseExtension` intercept methods are not called** (known issue with `@ApplyExtension` registration — `ConstructorExtension` and `PostInstantiationExtension` work because they're looked up differently in Kotest's engine)
- **Dev mode continuous testing** does not detect Kotest specs (requires Quarkus core changes)
- **Debug output**: The extension currently prints diagnostic `[QuarkusKotest]` lines to stderr — these will be removed in a future cleanup pass

## Requirements

- Quarkus 3.22+ (for `FacadeClassLoader` support)
- Kotest 6.0+ (for `@ApplyExtension` with `ConstructorExtension`)
- Kotlin 2.0+
- Java 17+