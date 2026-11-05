package `in`.doosripari.feature.circles

import `in`.doosripari.core.model.CircleType
import `in`.doosripari.core.model.JoinRule
import `in`.doosripari.core.model.Tier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The join rules of plan section 6.6, checked for reachability and for
 * agreement with `core-model`.
 *
 * Two things are being defended here. First, that every refusal is *typed and
 * reachable* — a boolean return would let a branch of this function go
 * untested forever, and untested branches are exactly the ones that say
 * "something else". Second, that [JoinRules.evaluate] never contradicts
 * `Circle.canJoin`, because the circle list screen uses the latter and a client
 * that says "join" where the model says "cannot" produces a 403 the user has no
 * way to act on.
 */
class JoinRulesTest {

    // ------------------------------------------------------------- the ladder --

    @Test
    fun `an open circle takes anybody who has signed up`() {
        val decision = JoinRules.evaluate(Fixtures.circle(joinRule = JoinRule.Open), Fixtures.user())
        assertIs<JoinDecision.Allow>(decision)
        assertTrue(decision.canJoin)
        assertFalse(decision.isRequest)
    }

    @Test
    fun `a T1 user passes a verified circle`() {
        val decision = JoinRules.evaluate(
            Fixtures.circle(joinRule = JoinRule.Verified),
            Fixtures.user(tier = Tier.T1),
        )
        assertIs<JoinDecision.Allow>(decision)
    }

    @Test
    fun `a T0 user with a phone on file is told to verify, and gets the T1 target`() {
        val decision = JoinRules.evaluate(
            Fixtures.circle(joinRule = JoinRule.Verified),
            Fixtures.user(tier = Tier.T0, phone = "+919876543210"),
        )
        val deny = assertIs<JoinDecision.Deny>(decision)
        assertEquals(JoinBlock.TierTooLow, deny.block)
        assertEquals(Tier.T1, deny.requiredTier)
        assertTrue(deny.message.isNotBlank())
    }

    @Test
    fun `a T0 user with no phone is told to verify the phone first`() {
        // This is the sentence the whole file exists for. A T0 user with no
        // number on file does not need a document; they need a one-minute OTP,
        // and "upgrade your verification" would send them looking for a
        // control that does not exist.
        val decision = JoinRules.evaluate(
            Fixtures.circle(joinRule = JoinRule.Verified),
            Fixtures.user(tier = Tier.T0, phone = null),
        )
        val deny = assertIs<JoinDecision.Deny>(decision)
        assertEquals(JoinBlock.PhoneUnverified, deny.block)
        assertEquals(Tier.T1, deny.requiredTier)
        assertTrue(deny.message.contains("phone"), "message was: ${deny.message}")
    }

    @Test
    fun `being one tier short produces the tier they are actually short of`() {
        // T1 asking for a document-verified circle. The button labels itself
        // from requiredTier, so getting this wrong points the user at an
        // upload they do not need yet.
        val decision = JoinRules.evaluate(
            Fixtures.circle(joinRule = JoinRule.DocumentVerified),
            Fixtures.user(tier = Tier.T1, phone = "+919876543210"),
        )
        val deny = assertIs<JoinDecision.Deny>(decision)
        assertEquals(JoinBlock.TierTooLow, deny.block)
        assertEquals(Tier.T2, deny.requiredTier)
        assertTrue(deny.requiredTier!!.rank > Fixtures.user(tier = Tier.T1).tier.rank)
    }

    @Test
    fun `a T2 user passes a document-verified circle`() {
        assertIs<JoinDecision.Allow>(
            JoinRules.evaluate(
                Fixtures.circle(joinRule = JoinRule.DocumentVerified),
                Fixtures.user(tier = Tier.T2),
            )
        )
    }

    @Test
    fun `nobody is offered a phone OTP for a tier an OTP cannot reach`() {
        // An OTP gets nobody to T2. Offering one here would be a promise the
        // app cannot keep.
        val decision = JoinRules.evaluate(
            Fixtures.circle(joinRule = JoinRule.DocumentVerified),
            Fixtures.user(tier = Tier.T0, phone = null),
        )
        val deny = assertIs<JoinDecision.Deny>(decision)
        assertEquals(JoinBlock.TierTooLow, deny.block)
        assertEquals(Tier.T2, deny.requiredTier)
    }

    // ------------------------------------------------------- already involved --

    @Test
    fun `a member is not told to fix something that is not broken`() {
        // The most specific reason wins, even on a circle that also happens to
        // be tier gated.
        val decision = JoinRules.evaluate(
            Fixtures.circle(joinRule = JoinRule.DocumentVerified, isJoined = true),
            Fixtures.user(tier = Tier.T0),
        )
        val deny = assertIs<JoinDecision.Deny>(decision)
        assertEquals(JoinBlock.AlreadyMember, deny.block)
    }

    @Test
    fun `an outstanding request is not a second request`() {
        val decision = JoinRules.evaluate(
            Fixtures.circle(joinRule = JoinRule.Approval, isPending = true),
            Fixtures.user(),
        )
        val deny = assertIs<JoinDecision.Deny>(decision)
        assertEquals(JoinBlock.ApprovalPending, deny.block)
    }

    // -------------------------------------------------------------- department --

    @Test
    fun `a department circle needs a department on record`() {
        val decision = JoinRules.evaluate(
            Fixtures.circle(joinRule = JoinRule.SameDepartment, departmentId = 12),
            Fixtures.user(departmentId = null),
        )
        val deny = assertIs<JoinDecision.Deny>(decision)
        assertEquals(JoinBlock.NoDepartmentOnRecord, deny.block)
    }

    @Test
    fun `the wrong department is a dead end and says so`() {
        val decision = JoinRules.evaluate(
            Fixtures.circle(joinRule = JoinRule.SameDepartment, departmentId = 12),
            Fixtures.user(departmentId = 34),
        )
        val deny = assertIs<JoinDecision.Deny>(decision)
        assertEquals(JoinBlock.DifferentDepartment, deny.block)
        assertFalse(deny.canJoin)
    }

    @Test
    fun `a department circle with no department on it is the circle's fault`() {
        val decision = JoinRules.evaluate(
            Fixtures.circle(joinRule = JoinRule.SameDepartment, departmentId = null),
            Fixtures.user(departmentId = 12),
        )
        val deny = assertIs<JoinDecision.Deny>(decision)
        assertEquals(JoinBlock.CircleNotConfigured, deny.block)
    }

    @Test
    fun `the right department gets in`() {
        assertIs<JoinDecision.Allow>(
            JoinRules.evaluate(
                Fixtures.circle(joinRule = JoinRule.SameDepartment, departmentId = 12),
                Fixtures.user(departmentId = 12),
            )
        )
    }

    // -------------------------------------------------------------- approval --

    @Test
    fun `an approval circle can be joined, but as a request`() {
        // "Join" and "Request to join" are different promises and must not
        // borrow each other's label.
        val decision = JoinRules.evaluate(
            Fixtures.circle(joinRule = JoinRule.Approval),
            Fixtures.user(),
        )
        val allow = assertIs<JoinDecision.Allow>(decision)
        assertTrue(allow.asRequest)
        assertTrue(allow.isRequest)
        assertTrue(allow.message.contains("request", ignoreCase = true) ||
            allow.message.contains("Ask", ignoreCase = true))
    }

    // ----------------------------------------------------- every reason is live --

    @Test
    fun `every block has a sentence and a round trip through its wire code`() {
        // A block with a blank message is a greyed-out button, which is the
        // thing this file was written to avoid.
        for (block in JoinBlock.entries) {
            assertTrue(block.message.isNotBlank(), "$block has no message")
            assertTrue(block.message.endsWith("."), "$block's message has no full stop")
            assertEquals(block, JoinBlock.fromCode(block.code))
        }
        assertEquals(JoinBlock.TierTooLow, JoinBlock.fromCode("a_reason_from_the_future"))
        assertEquals(JoinBlock.TierTooLow, JoinBlock.fromCode(null))
    }

    @Test
    fun `the decisions never contradict Circle canJoin`() {
        // `core-model` is what the server and the circle list use. If the two
        // disagree, one of them is producing a button that 403s.
        val circles = listOf(
            Fixtures.circle(joinRule = JoinRule.Open),
            Fixtures.circle(joinRule = JoinRule.Verified),
            Fixtures.circle(joinRule = JoinRule.DocumentVerified),
            Fixtures.circle(joinRule = JoinRule.Approval),
            Fixtures.circle(joinRule = JoinRule.SameDepartment, departmentId = 12),
            Fixtures.circle(joinRule = JoinRule.Open, isJoined = true),
            Fixtures.circle(joinRule = JoinRule.Verified, isPending = true),
        )
        val users = listOf(
            Fixtures.user(tier = Tier.T0, phone = null),
            Fixtures.user(tier = Tier.T0, phone = "+919876543210"),
            Fixtures.user(tier = Tier.T1, departmentId = 12),
            Fixtures.user(tier = Tier.T3, departmentId = 99),
        )

        for (circle in circles) {
            for (user in users) {
                assertEquals(
                    circle.canJoin(user),
                    JoinRules.evaluate(circle, user).canJoin,
                    "disagreement for $circle / $user",
                )
            }
        }
    }

    // --------------------------------------------------------------- posting --

    @Test
    fun `a T0 member may join an open circle but may not post in it`() {
        // Plan section 6.6's batch row says posting needs T1, and section 6.1
        // defines T0 as being able to check in and be escalated to and to host
        // nothing. The rule is applied to every circle, not only batches.
        val circle = Fixtures.circle(joinRule = JoinRule.Open, isJoined = true)
        val t0 = Fixtures.user(tier = Tier.T0)

        assertTrue(JoinRules.evaluate(circle, t0).canJoin)

        val post = JoinRules.evaluatePosting(circle, t0)
        val deny = assertIs<PostDecision.Deny>(post)
        assertEquals(PostBlock.TierTooLowToPost, deny.block)
        assertEquals(Tier.T1, deny.requiredTier)
        assertTrue(deny.message.isNotBlank())
    }

    @Test
    fun `a T1 member can post`() {
        assertEquals(
            PostDecision.Allowed,
            JoinRules.evaluatePosting(
                Fixtures.circle(joinRule = JoinRule.Open, isJoined = true),
                Fixtures.user(tier = Tier.T1),
            ),
        )
    }

    @Test
    fun `somebody who is not in the circle is told to join before they post`() {
        val post = JoinRules.evaluatePosting(
            Fixtures.circle(joinRule = JoinRule.Open, isJoined = false),
            Fixtures.user(tier = Tier.T3),
        )
        assertEquals(PostBlock.NotAMember, assertIs<PostDecision.Deny>(post).block)
    }

    @Test
    fun `somebody whose request is pending is told to wait, not to re-request`() {
        val post = JoinRules.evaluatePosting(
            Fixtures.circle(joinRule = JoinRule.Approval, isPending = true),
            Fixtures.user(tier = Tier.T3),
        )
        assertEquals(PostBlock.ApprovalPending, assertIs<PostDecision.Deny>(post).block)
    }

    @Test
    fun `every post block round trips and has a sentence`() {
        for (block in PostBlock.entries) {
            assertTrue(block.message.isNotBlank(), "$block has no message")
            assertEquals(block, PostBlock.fromCode(block.code))
        }
    }

    // ------------------------------------------------------------ suggestions --

    @Test
    fun `batches are suggested first`() {
        // The people a pensioner most wants to hear from are the ones they
        // served with, and section 6.6's own table leads with the batch.
        val order = JoinRules.suggestedOrder()
        assertEquals(CircleType.Batch, order.first())
        assertEquals(CircleType.entries.toSet(), order.toSet())
    }
}
