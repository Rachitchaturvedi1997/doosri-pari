package `in`.doosripari.feature.sahayak

import `in`.doosripari.core.model.SahayakIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Intent classification, which plan 6.4 step 2 puts before anything else.
 *
 * Two things are being pinned. That a clear question is read correctly and
 * *sayably* — the matched terms are what a user is shown to confirm the
 * reading, so a correct answer with no explanation behind it is not shippable.
 * And that an ambiguous question says it is ambiguous, because the tie is broken
 * by weight order and nothing else, and a hidden tie is a wrong answer wearing
 * a confident face.
 */
class IntentTest {

    private fun read(text: String) = IntentClassifier.classify(text)

    // ------------------------------------------------------------------ the clear case --

    @Test
    fun `a commutation question that wants a figure is a calculation`() {
        val result = read("How much can I commute from my pension?")

        assertEquals(SahayakIntent.Commutation, result.intent)
        assertEquals(SahayakQuestionKind.Calculation, result.kind)
        assertFalse(result.isAmbiguous, "only one subject was named")
        assertEquals(0.0, result.runnerUpScore)
    }

    /**
     * The rule this file exists to defend: a first-person question that wants a
     * number is a calculator question, not a personal case.
     *
     * "My" alone must not route somebody to a volunteer, because the toolkit
     * answers that question exactly and being handed off would make the app
     * refuse to do the one thing it does well.
     */
    @Test
    fun `a first-person question that wants a figure is not a personal case`() {
        val result = read("How much can I commute from my pension?")

        assertEquals(SahayakQuestionKind.Calculation, result.kind)
        assertFalse(
            result.kind == SahayakQuestionKind.PersonalCase,
            "\"my pension\" is not the same as naming my own record",
        )
    }

    @Test
    fun `naming my own record is a personal case`() {
        val result = read("My arrears for 2021 have still not come")

        assertEquals(SahayakIntent.Arrears, result.intent)
        assertEquals(SahayakQuestionKind.PersonalCase, result.kind)
        assertEquals(PeriodReference.Past, result.period, "2021 in the question is a past period")
    }

    @Test
    fun `a life certificate question is a process, and a subject all the same`() {
        val result = read("How do I get a life certificate?")

        // The life certificate has no SahayakIntent of its own, so it is read
        // under the slip subject — both are "prove you are still alive to the
        // office". Leaving it out of the table classified the question as
        // nothing at all, which cost the offline path its life-certificate
        // answer entirely.
        assertEquals(SahayakIntent.PensionSlip, result.intent)
        assertEquals(SahayakQuestionKind.Process, result.kind)
        assertTrue(
            result.kindTerms.any { it.phrase == "how do i" },
            "and says which words decided the kind",
        )
    }

    @Test
    fun `the matched terms are the ones that decided the subject`() {
        val result = read("What is the DA rate now?")

        assertEquals(SahayakIntent.DearnessAllowance, result.intent)
        // Both fire, and both are shown. Hiding the compound behind "da" alone
        // would leave the user unable to see why they were read this way.
        assertEquals(listOf("da", "da rate"), result.matchedTerms.map { it.phrase })
        assertEquals(SahayakQuestionKind.RuleQuestion, result.kind)
        assertEquals(PeriodReference.Current, result.period)
    }

    @Test
    fun `the plan's synonyms are the same word to the classifier`() {
        // DA / DR / dearness relief.
        for (phrase in listOf("DA", "DR", "dearness relief")) {
            assertEquals(
                SahayakIntent.DearnessAllowance,
                read("tell me about $phrase").intent,
                "\"$phrase\" should reach the same subject",
            )
        }
        // Commutation / lump sum.
        for (phrase in listOf("commutation", "lump sum")) {
            assertEquals(SahayakIntent.Commutation, read("the $phrase rules").intent, "\"$phrase\"")
        }
        // Family pension / widow pension / parivarik pension.
        for (phrase in listOf("family pension", "widow pension", "parivarik pension")) {
            assertEquals(SahayakIntent.FamilyPension, read("the $phrase rules").intent, "\"$phrase\"")
        }
    }

    // ---------------------------------------------------------------- the ambiguous case --

    @Test
    fun `a question naming two subjects is flagged as ambiguous and keeps both`() {
        val result = read("Dearness relief arrears not received since 2019")

        assertTrue(result.isAmbiguous, "two subjects, and the tie is not a secret")
        assertEquals(SahayakIntent.DearnessAllowance, result.candidates[0].intent)
        assertEquals(SahayakIntent.Arrears, result.candidates[1].intent)
        assertTrue(
            result.runnerUpScore >= result.candidates[0].score * IntentClassifier.AMBIGUITY_RATIO,
            "the runner-up is within the ratio that defines ambiguous",
        )
    }

    @Test
    fun `both subjects keep their own matched terms`() {
        val result = read("Dearness relief arrears not received since 2019")

        val da = result.candidates.first { it.intent == SahayakIntent.DearnessAllowance }
        val arrears = result.candidates.first { it.intent == SahayakIntent.Arrears }

        assertTrue(da.matchedTerms.any { it.phrase == "dearness relief" })
        assertTrue(arrears.matchedTerms.any { it.phrase == "arrears" })
        // The grievance reading is separate from the subject, and it is what
        // decides what happens next.
        assertEquals(SahayakQuestionKind.Grievance, result.kind)
        assertTrue(result.kindTerms.any { it.phrase == "not received" })
    }

    @Test
    fun `a question naming one subject is not ambiguous`() {
        val result = read("What is the DA rate now?")
        assertEquals(1, result.candidates.size)
        assertFalse(result.isAmbiguous)
    }

    /**
     * Repetition must not buy a subject.
     *
     * If occurrences multiplied the score, "da da da" would outrank a careful
     * question naming three different subjects, and the classifier would give a
     * different answer to the same question depending on how it was typed.
     */
    @Test
    fun `repeating a word does not raise its subject's score`() {
        val once = read("What is the DA rate now?").candidates.single().score
        val thrice = read("DA DA DA what is the rate now").candidates
            .first { it.intent == SahayakIntent.DearnessAllowance }
        assertEquals(3, thrice.matchedTerms.first { it.phrase == "da" }.occurrences)
        assertEquals(once, thrice.score, "three occurrences score the same as one")
    }

    // ------------------------------------------------------------------ out of scope --

    @Test
    fun `an investment question is refused as out of scope, vetoing its subject`() {
        val result = read("Should I invest my pension arrears in a mutual fund?")

        assertEquals(SahayakQuestionKind.OutOfScope, result.kind)
        assertTrue(result.isExplicitlyOutOfScope)
        // The subject is still read — the user did say "arrears" — and it is the
        // veto, not the absence of a subject, that refuses it.
        assertEquals(SahayakIntent.Arrears, result.intent)
    }

    /**
     * The distinction that stops a gap in this file being reported to a pensioner
     * as a gap in their question.
     */
    @Test
    fun `a pension question this file cannot read is not out of scope`() {
        val result = read("What is the quantum of the quarterly revision on the phani shulk?")

        assertEquals(SahayakIntent.Unknown, result.intent)
        assertFalse(
            result.isExplicitlyOutOfScope,
            "no subject matched, but the user did not ask anything forbidden",
        )
    }

    @Test
    fun `a question of neither sort is neither`() {
        val result = read("xyzzy plugh")

        assertEquals(SahayakIntent.Unknown, result.intent)
        assertFalse(result.isExplicitlyOutOfScope)
        assertTrue(result.candidates.isEmpty())
    }

    // ------------------------------------------------------------------- the periods --

    @Test
    fun `a year in the question is a past period`() {
        assertEquals(PeriodReference.Past, read("arrears for 2016").period)
    }

    @Test
    fun `a current marker keeps the question current even with a year in it`() {
        // A question about the present with a historical part, where quoting an
        // older rule is allowed and quoting only the newest would be incomplete.
        assertEquals(PeriodReference.Current, read("what was the DA rate in 2016 and what is it now").period)
    }

    @Test
    fun `a question with no time in it says so`() {
        assertEquals(PeriodReference.Unstated, read("what are the family pension rules").period)
    }

    @Test
    fun `a past marker and a current marker each name their own period`() {
        assertEquals(PeriodReference.Past, read("what was the old rule").period)
        assertEquals(PeriodReference.Current, read("the current rule").period)
    }

    /**
     * The term table matches whole phrases, so a plural is a different word.
     *
     * "old rules" is not "old rule" and is not read as one. Asserted because it
     * is a real gap — a pensioner who says "under the old rules" in the plural
     * gets today's rule quoted at them — and the fix is a term table, not a
     * rule. See the module report.
     */
    @Test
    fun `a plural past marker is not the singular one, and that is a known gap`() {
        assertEquals(PeriodReference.Unstated, read("under the old rules").period)
    }

    // ------------------------------------------------------------------ the tokenising --

    @Test
    fun `Devanagari keeps its matras attached`() {
        // A class of [java.lang.Character.LETTER] alone splits `मेरा` into two
        // tokens, and then no Devanagari phrase can ever match.
        assertEquals(listOf("मेरा", "da"), SahayakText.tokens("मेरा DA"))
    }

    @Test
    fun `a Devanagari question is read for the words it shares in Latin script`() {
        val result = read("मेरे DA arrears कितने हुए")
        assertEquals(
            SahayakIntent.Arrears,
            result.intent,
            "the subject words survive even when the sentence does not",
        )
    }

    /**
     * The known limitation, asserted rather than left in a comment.
     *
     * `कितने` is "kitne", and the table holds it in romanised script only, so a
     * question typed in Devanagari is read as a rule question rather than a
     * calculation. It is still answered — the subject is right — but the
     * calculator route is missed. Fixing it means a Devanagari term table, which
     * belongs with a native speaker; see the module report.
     */
    @Test
    fun `a Devanagari calculation is read as a rule question, and the report says so`() {
        val result = read("मेरे DA arrears कितने हुए")
        assertEquals(SahayakQuestionKind.RuleQuestion, result.kind)
    }

    @Test
    fun `Devanagari past markers are held in Devanagari as well as romanised`() {
        assertEquals(PeriodReference.Past, read("पिछले साल").period)
        assertEquals(PeriodReference.Past, read("pichle saal").period)
    }

    // ------------------------------------------------------------- the prefix index --

    @Test
    fun `one word is indexed by its first six characters`() {
        assertEquals(
            listOf("commut", "commut", "commut"),
            Retriever.contentTokens("commute commuted commuting"),
        )
    }

    @Test
    fun `the synonym list is published in the same units it is matched in`() {
        val tokens = IntentClassifier.synonymIndexTokens(SahayakIntent.Commutation)
        assertTrue(tokens.contains("commut"), "so a passage saying commutation aligns")
        assertTrue(tokens.contains("gratu"), "and so does one saying gratuity")
    }

    @Test
    fun `a subject with no table has no synonyms rather than an error`() {
        assertTrue(IntentClassifier.synonymIndexTokens(SahayakIntent.Unknown).isEmpty())
    }

    // ---------------------------------------------------------------------- the codes --

    @Test
    fun `every kind round trips through its code and falls back safely`() {
        for (kind in SahayakQuestionKind.entries) {
            assertEquals(kind, SahayakQuestionKind.fromCode(kind.code))
        }
        assertEquals(
            SahayakQuestionKind.OutOfScope,
            SahayakQuestionKind.fromCode("not_a_kind"),
            "an unrecognised code is out of scope, not a crash",
        )
        assertEquals(
            SahayakQuestionKind.OutOfScope,
            SahayakQuestionKind.fromCode(null),
        )
    }

    @Test
    fun `every period reference round trips and falls back safely`() {
        for (period in PeriodReference.entries) {
            assertEquals(period, PeriodReference.fromCode(period.code))
        }
        assertEquals(PeriodReference.Unstated, PeriodReference.fromCode("nonsense"))
        assertEquals(PeriodReference.Unstated, PeriodReference.fromCode(null))
    }

    @Test
    fun `the reading is shown to the user in a sentence`() {
        assertTrue(read("How much can I commute from my pension?").summary.contains("commutation"))
        assertTrue(
            read("Should I invest in a mutual fund?").summary.contains("not a pension question"),
        )
    }

    @Test
    fun `a blank question reads as nothing rather than throwing`() {
        val result = read("")
        assertEquals(SahayakIntent.Unknown, result.intent)
        assertEquals(SahayakQuestionKind.OutOfScope, result.kind)
        assertEquals(PeriodReference.Unstated, result.period)
    }
}
