package io.github.iamjosephmj.bench

data class CorpusItem(val id: String, val kind: Kind, val profile: Profile) {
    enum class Kind(val bytes: Long, val chunks: Int) {
        PING(4_096, 0), MEDIUM_SYNC(5_000_000, 0), LARGE_CHUNKED(200_000_000, 40)
    }
    enum class Profile { NONE, UNMETERED_CHARGING }
}

val CORPUS: List<CorpusItem> = CorpusItem.Kind.entries.flatMap { kind ->
    CorpusItem.Profile.entries.map { profile ->
        CorpusItem("${kind.name.lowercase()}-${profile.name.lowercase()}", kind, profile)
    }
}
