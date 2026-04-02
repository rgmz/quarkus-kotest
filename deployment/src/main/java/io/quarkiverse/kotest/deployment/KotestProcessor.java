package io.quarkiverse.kotest.deployment;

import java.util.HashSet;
import java.util.Set;

import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationTransformation;
import org.jboss.jandex.DotName;

import io.quarkus.arc.deployment.AnnotationsTransformerBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.RemovedResourceBuildItem;
import io.quarkus.maven.dependency.ArtifactKey;

class KotestProcessor {

    private static final DotName APPLY_EXTENSION = DotName.createSimple("io.kotest.core.extensions.ApplyExtension");
    private static final DotName TEST_TRANSACTION = DotName.createSimple("io.quarkus.test.TestTransaction");

    private static final String FEATURE = "kotest";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * Strip interceptor bindings (like @TestTransaction) from Kotest spec classes
     * so that ArC does not generate subclass proxies for them. Kotest's
     * TestConfiguration has final methods that cannot be overridden by proxies.
     * The @TestTransaction behavior is handled manually by QuarkusKotestHelper.
     *
     * We intentionally do NOT exclude specs from CDI discovery — ArC needs to see
     * their @Inject fields to know which beans are required (preventing false-positive
     * unused bean removal).
     */
    @BuildStep
    void stripInterceptorBindingsFromKotestSpecs(
            CombinedIndexBuildItem index,
            BuildProducer<AnnotationsTransformerBuildItem> annotationsTransformers) {
        Set<String> specClassNames = new HashSet<>();
        for (var annotation : index.getIndex().getAnnotations(APPLY_EXTENSION)) {
            if (annotation.target().kind() == AnnotationTarget.Kind.CLASS) {
                specClassNames.add(annotation.target().asClass().name().toString());
            }
        }
        if (!specClassNames.isEmpty()) {
            annotationsTransformers.produce(new AnnotationsTransformerBuildItem(
                    AnnotationTransformation.forClasses()
                            .whenClass(c -> specClassNames.contains(c.name().toString()))
                            .transform(ctx -> ctx.remove(
                                    ann -> ann.name().equals(TEST_TRANSACTION)))));
        }
    }

    /**
     * Remove Kotlin types that cross the Kotest engine / QuarkusClassLoader boundary
     * from QuarkusClassLoader's view of kotlin-stdlib. This forces parent-classloader
     * delegation for these specific types (FunctionN, KClass, coroutine types, Unit).
     *
     * Note: kotlin-stdlib is also configured as a parentFirstArtifact in
     * runtime/pom.xml, which currently makes this removal redundant. Both mechanisms
     * are kept because (a) there is no BuildItem equivalent of parentFirstArtifacts,
     * and (b) this build step documents exactly which types cross the boundary and
     * will become the sole mechanism if parent-first can be removed in the future.
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
