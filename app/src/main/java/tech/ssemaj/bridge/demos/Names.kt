package tech.ssemaj.bridge.demos

/** Worker + work-item names shared between the Application registration and the UI. */
object Names {
    // Worker implementations (registered in BridgeShowcaseApp).
    const val WORKER_SIMPLE = "demo-worker"
    const val WORKER_CHUNKED = "demo-chunked-worker"

    // Work-item names (what you enqueue, diagnose, and cancel by).
    const val SIMPLE = "demo-simple"
    const val CONSTRAINED = "demo-constrained"
    const val CHUNKED = "demo-chunked"
    const val DURABLE = "demo-durable"
    const val DEADLINE = "demo-deadline"
    const val PERIODIC = "demo-periodic"
    const val COMPAT_CHAIN = "demo-compat-chain"
}
