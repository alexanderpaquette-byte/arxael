package dev.arxael.adapter

import dev.arxael.adapter.gradle.GradleAdapter

/** Name → adapter lookup. New language adapters register here only. */
class AdapterRegistry private constructor(private val adapters: Map<String, BuildAdapter>) {
    fun get(name: String): BuildAdapter? = adapters[name]
    fun names(): Set<String> = adapters.keys

    companion object {
        fun default(): AdapterRegistry = AdapterRegistry(
            (listOf(GradleAdapter(), ExecAdapter(), NoopAdapter()) + CommandAdapter.languageDefaults())
                .associateBy { it.name },
        )

        /** Build a registry from an explicit adapter list (tests / custom deployments). */
        fun of(vararg adapters: BuildAdapter): AdapterRegistry = AdapterRegistry(adapters.associateBy { it.name })
    }
}
