package `in`.doosripari.feature.kendra

/**
 * A string to show, as a catalogue key plus its arguments.
 *
 * ## Why there is no English in this module
 *
 * Plan section 6.16 ships ten languages, each translated by a person and reviewed
 * by a native speaker over 60, and section 3 asks for the first screen in the
 * user's own script. That is a catalogue, and the catalogue lives in each
 * platform's string resources where the translators and the review tooling can
 * reach it.
 *
 * It matters more here than elsewhere, because the sentences this type carries
 * are the ones a person reads when the app has just told them it is not going to
 * let them do something. "We have left this one out because of your knee" has to
 * be right in Tamil by somebody who knows what a grandmother says and not by a
 * developer guessing. So a typed [ExclusionReason] plus a key, never a sentence
 * in a Kotlin file.
 *
 * `feature-health` declares an identical type for the same reason. Both belong
 * in `core-model`; raised as a finding rather than fixed from here.
 */
data class Phrase(
    /** The catalogue key, namespaced by feature. Stable: it is in shipped apps. */
    val key: String,
    val args: Map<String, String> = emptyMap(),
) {
    init {
        require(key.isNotBlank()) { "a phrase with no key is a bug, not a message" }
    }
}
