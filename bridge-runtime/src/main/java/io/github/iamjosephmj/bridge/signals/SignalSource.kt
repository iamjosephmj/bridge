package io.github.iamjosephmj.bridge.signals

interface SignalSource {
    val kind: SignalKind
    fun read(): SignalValue
}

class FakeSignalSource(
    override val kind: SignalKind,
    var value: SignalValue,
) : SignalSource {
    override fun read(): SignalValue = value
}
