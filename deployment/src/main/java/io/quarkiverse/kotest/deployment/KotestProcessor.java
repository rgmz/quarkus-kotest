package io.quarkiverse.kotest.deployment;

import java.util.HashSet;
import java.util.Set;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.RemovedResourceBuildItem;
import io.quarkus.maven.dependency.ArtifactKey;

class KotestProcessor {

    private static final String FEATURE = "kotest";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * Remove the stateless kotlin.Function and kotlin.jvm.functions.Function0-22
     * interfaces from QuarkusClassLoader's view of kotlin-stdlib. This forces
     * parent-classloader delegation for just these types, so both the Kotest engine
     * (parent CL) and spec classes (QuarkusClassLoader) see the same Function2 identity.
     * Avoids making all of kotlin-stdlib parent-first, which can break SmallRye
     * context propagation and other recorder statics.
     */
    @BuildStep
    RemovedResourceBuildItem removeKotlinFunctionTypes() {
        Set<String> resources = new HashSet<>();

        // Function interfaces (Function0-Function22 + base Function)
        // Used in Kotest's suspend lambdas and test DSL closures
        resources.add("kotlin/Function.class");
        for (int i = 0; i <= 22; i++) {
            resources.add("kotlin/jvm/functions/Function" + i + ".class");
        }

        // KClass and its superinterfaces — used in ConstructorExtension.instantiate(KClass<T>)
        resources.add("kotlin/reflect/KClass.class");
        resources.add("kotlin/reflect/KDeclarationContainer.class");
        resources.add("kotlin/reflect/KAnnotatedElement.class");
        resources.add("kotlin/reflect/KClassifier.class");

        // Coroutine types — suspend functions compile to Continuation-passing style
        resources.add("kotlin/coroutines/Continuation.class");
        resources.add("kotlin/coroutines/CoroutineContext.class");
        resources.add("kotlin/coroutines/CoroutineContext$Element.class");
        resources.add("kotlin/coroutines/CoroutineContext$Key.class");

        // Unit — return type of suspend functions
        resources.add("kotlin/Unit.class");

        return new RemovedResourceBuildItem(
                ArtifactKey.fromString("org.jetbrains.kotlin:kotlin-stdlib"),
                resources);
    }

}
