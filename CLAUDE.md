# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Quarkiverse extension that integrates the Kotest testing framework with Quarkus. Currently in skeleton/early-implementation stage. The core challenge is that Kotest is a JUnit Platform *engine* (sibling to Jupiter), so Quarkus's `@QuarkusTest` Jupiter extensions cannot be reused directly. The implementation strategy (documented in `PLANS/`) uses native Kotest extensions (`ConstructorExtension`, `PostInstantiationExtension`, etc.) to bootstrap Quarkus and manage CDI injection.

## Build Commands

```bash
# Full build (CI-equivalent)
mvn -B clean install -Dno-format

# Native image build
mvn -B install -Dnative -Dquarkus.native.container-build -Dnative.surefire.skip

# Run all tests
mvn test

# Run a specific test class
mvn test -pl deployment -Dtest=KotestTest

# Run integration tests (skipped by default)
mvn verify -pl integration-tests -DskipITs=false

# Build a single module
mvn -pl runtime clean install
mvn -pl deployment clean install
```

## Architecture

This follows the standard **Quarkus Extension** two-module pattern, inheriting from `quarkiverse-parent`:

- **`runtime/`** — Runtime extension artifact (`quarkus-kotest`). Contains classes available at application runtime. Depends on `quarkus-arc` for CDI.
- **`deployment/`** — Build-time processing artifact (`quarkus-kotest-deployment`). Contains `@BuildStep` processors that run during Quarkus augmentation. Currently only `KotestProcessor` registering the `"kotest"` feature.
- **`integration-tests/`** — Sample Quarkus app with REST endpoint and tests. Activated via the `it` profile (on by default, off during releases).
- **`docs/`** — Antora-format documentation. Activated via the `docs` profile.

The runtime module's `quarkus-extension-maven-plugin` generates `META-INF/quarkus-extension.properties` linking runtime to its deployment counterpart. The deployment module uses `quarkus-extension-processor` annotation processor to generate extension metadata.

## Key Configuration

- **Java**: 17+ (`maven.compiler.release=17`)
- **Quarkus**: 3.31.4 (managed via `quarkus-bom`)
- **Parent**: `io.quarkiverse:quarkiverse-parent:20`
- **Surefire patterns**: `**/*Test.*` and `**/*Spec.*` (supports both JUnit and Kotest naming)
- **Integration tests**: `skipITs=true` by default; the `native-image` profile sets `skipITs=false`

## Design Documents

The `PLANS/` directory contains detailed research and implementation design:
- **CHAPTER1**: Background on Kotest-Quarkus incompatibility (classloader, engine vs. extension gap)
- **CHAPTER2**: Three implementation strategies ranked; Strategy 2 (native Kotest extensions) recommended
- **CHAPTER3**: Detailed implementation roadmap for Strategy 2, including classloader management and the four planned extension components (`QuarkusProjectExtension`, `QuarkusConstructorExtension`, `QuarkusPostInstantiationExtension`, `QuarkusTestCaseExtension`)
