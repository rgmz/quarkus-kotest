# Quarkus Kotest

A Quarkus extension that integrates the [Kotest](https://kotest.io/) testing framework with Quarkus CDI. Write native Kotest specs (`FunSpec`, `StringSpec`, `BehaviorSpec`, etc.) with full CDI injection, test transactions, and REST endpoint testing.

## Quick Start

```kotlin
@QuarkusTest
@ApplyExtension(QuarkusKotestExtension::class)
class GreetingServiceSpec : FunSpec() {
    @Inject
    lateinit var service: GreetingService

    init {
        test("greeting works") {
            service.greet("world") shouldBe "Hello, world!"
        }
    }
}
```

With `@TestTransaction` for automatic database rollback:

```kotlin
@QuarkusTest
@TestTransaction
@ApplyExtension(QuarkusKotestExtension::class)
class UserRepositorySpec : FunSpec() {
    @Inject
    lateinit var userRepo: UserRepository

    init {
        test("can persist a user") {
            val user = User(name = "Alice")
            userRepo.persist(user)
            userRepo.findByName("Alice")!!.name shouldBe "Alice"
            // Transaction rolls back here -- database is clean for next test
        }

        test("database is clean from previous test") {
            userRepo.findByName("Alice") shouldBe null
        }
    }
}
```

REST endpoint testing with RestAssured:

```kotlin
@QuarkusTest
@ApplyExtension(QuarkusKotestExtension::class)
class GreetingResourceSpec : FunSpec() {
    init {
        beforeSpec {
            RestAssured.port = Integer.getInteger("quarkus.http.test-port", 8081)
        }

        test("GET /hello returns 200") {
            given()
                .`when`().get("/hello")
                .then()
                .statusCode(200)
                .body(`is`("Hello from Quarkus REST"))
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

## Using in Your Project

### 1. Add dependencies

```xml
<properties>
    <kotest.version>6.1.10</kotest.version>
    <quarkus-kotest.version>1.0.0-SNAPSHOT</quarkus-kotest.version>
</properties>

<dependencies>
    <!-- Quarkus Kotest extension -->
    <dependency>
        <groupId>io.quarkiverse.kotest</groupId>
        <artifactId>quarkus-kotest</artifactId>
        <version>${quarkus-kotest.version}</version>
    </dependency>

    <!-- Kotest runner + assertions -->
    <dependency>
        <groupId>io.kotest</groupId>
        <artifactId>kotest-runner-junit6-jvm</artifactId>
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
```

### 3. Configure Surefire

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

### 4. Write your tests

Place Kotest specs in `src/test/kotlin/`. Every spec needs these annotations:

```kotlin
@QuarkusTest                                       // triggers Quarkus test lifecycle
@ApplyExtension(QuarkusKotestExtension::class)     // registers the Kotest extension
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

## Features

- **CDI injection** -- `@Inject` fields are resolved from the ArC container
- **All Kotest spec styles** -- `FunSpec`, `StringSpec`, `BehaviorSpec`, `DescribeSpec`, etc.
- **`@TestTransaction`** -- automatic transaction begin/rollback per test for database isolation
- **REST endpoint testing** -- RestAssured works with the Quarkus test HTTP port
- **Jupiter coexistence** -- plain JUnit tests can exist alongside Kotest specs in the same module

## How It Works

The extension bridges the Kotest engine (system classloader) with Quarkus (QuarkusClassLoader):

1. `@QuarkusTest` triggers Quarkus's `FacadeClassLoader` to route the spec through `QuarkusClassLoader` (augmentation, CDI setup)
2. `ConstructorExtension` creates spec instances through `QuarkusClassLoader` so CDI types are compatible
3. `PostInstantiationExtension` delegates to `QuarkusKotestHelper` (loaded by QuarkusClassLoader) which calls `Arc.container()` directly to inject `@Inject` fields
4. `TestCaseExtension` activates/deactivates CDI test scope per test, and wraps `@TestTransaction` specs in begin/rollback
5. A `@BuildStep` strips `@TestTransaction` from spec classes in the Jandex index so ArC doesn't generate subclass proxies (Kotest's `TestConfiguration` has final methods)

## Current Limitations

- **No mixed `@QuarkusTest` across engines** -- if both JUnit Jupiter and Kotest specs use `@QuarkusTest` in the same Surefire fork, Jupiter shuts down the app before Kotest runs. The extension detects this and provides a clear error message. Workaround: keep `@QuarkusTest` on Kotest specs only, or put them in separate modules.
- **No CDI qualifier support** -- `@Inject` resolves beans by type only; `@Named`, custom qualifiers, and `@Dependent` bean cleanup are not yet supported (planned)
- **No `@InjectMock` / `QuarkusMock` support** (planned)
- **No `@TestProfile` support** (planned)
- **No `@QuarkusTestResource` support** (planned)
- **No `@TestHTTPResource` / `@TestHTTPEndpoint` support** (planned)
- **RestAssured port must be configured manually** via `beforeSpec { RestAssured.port = Integer.getInteger("quarkus.http.test-port", 8081) }`
- **Dev mode continuous testing** does not detect Kotest specs (requires Quarkus core changes)
- **Debug output** -- the extension prints diagnostic `[QuarkusKotest]` lines to stderr (will be removed before release)

## Requirements

- Quarkus 3.22+ (for `FacadeClassLoader` support)
- Kotest 6.1+ (for `@ApplyExtension` with `ConstructorExtension`)
- Kotlin 2.0+
- Java 17+