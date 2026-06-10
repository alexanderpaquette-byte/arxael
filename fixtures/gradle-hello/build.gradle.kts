// Trivial, dependency-free gradle project used by the smoke test (and as the seed for the
// benchmark fixture). `smoke` does a real compile then prints a sentinel the smoke test greps for.
plugins {
    java
}

tasks.register("smoke") {
    dependsOn("compileJava")
    doLast {
        println("ARXAEL_SMOKE_OK")
    }
}
