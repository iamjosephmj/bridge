package io.github.iamjosephmj.bridge.api

class WorkerRegistry {
    private val factories = mutableMapOf<String, () -> BridgeWorker>()

    fun register(name: String, factory: () -> BridgeWorker) {
        factories[name] = factory
    }

    fun create(name: String): BridgeWorker =
        (factories[name] ?: throw IllegalArgumentException(
            "No worker registered for '$name'. Call WorkerRegistry.register(\"$name\") { ... } during Bridge.initialize.")
        ).invoke()
}
