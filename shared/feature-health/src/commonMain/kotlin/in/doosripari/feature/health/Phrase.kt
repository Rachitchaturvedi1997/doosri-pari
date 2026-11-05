package `in`.doosripari.feature.health

/**
 * A string to show, as a catalogue key plus its arguments.
 *
 * ## Why there is no English in this module
 *
 * Plan section 6.16 ships ten languages, each translated by a person and
 * reviewed by a native speaker over 60, and section 3 says the first screen
 * asks for language with every name in its own script. That is a catalogue, and
 * the catalogue lives in each platform's string resources where the translators
 * and the string-review tooling can see it. A sentence typed into a Kotlin file
 * is a sentence that ships to all ten languages in English and cannot be
 * reviewed by the person whose job is to review it.
 *
 * So this module produces keys and arguments and never prose. `feature-kendra`
 * declares an identical type for its own reasons; both should be promoted to
 * `core-model` rather than living twice, and that is raised as a finding.
 *
 * [args] values are **numbers, names and dates already formatted by the caller**
 * — the caller's platform owns number and date formatting too, because
 * lakh/crore grouping and the Bengali calendar are not things to reimplement in
 * shared code.
 */
data class Phrase(
    /** The catalogue key, namespaced by feature. Stable: it is in shipped apps. */
    val key: String,
    val args: Map<String, String> = emptyMap(),
) {
    init {
        require(key.isNotBlank()) { "a phrase with no key is a bug, not a message" }
    }

    /** Convenience for the one-argument case, which is most of them. */
    constructor(key: String, arg: String, argName: String) : this(key, mapOf(argName to arg))
}
