package io.quarkiverse.kotest.it;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Plain CDI bean with no REST or JPA annotations. Only referenced via @Inject
 * in Kotest specs — validates that ArC does not remove it as unused.
 */
@ApplicationScoped
public class GreetingService {

    public String greet(String name) {
        return "Hello, " + name + "!";
    }
}
