package com.mediahub.pear.onboarding

import androidx.compose.ui.unit.dp
import com.mediahub.pear.data.ContentCategory
import com.mediahub.pear.data.Profile
import com.mediahub.pear.shell.LayoutOverride
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingRulesTest {
    private val me = Profile("1", "Me", "#7D5CFF")

    @Test
    fun stepsRunInTheOrderOfTheIosSetup() {
        val order = generateSequence(OnboardingStep.Welcome) { it.next() }.toList()
        assertEquals(
            listOf(
                OnboardingStep.Welcome,
                OnboardingStep.Profile,
                OnboardingStep.Look,
                OnboardingStep.Content,
                OnboardingStep.Home,
                OnboardingStep.Keys,
                OnboardingStep.Done,
            ),
            order,
        )
        assertNull(OnboardingStep.Welcome.previous())
        assertNull(OnboardingStep.Done.next())
        assertEquals(OnboardingStep.Keys, OnboardingStep.Done.previous())
    }

    @Test
    fun theProfileStepNeedsSimklOrAName() {
        assertFalse(OnboardingRules.canContinue(OnboardingStep.Profile, simklActive = false, name = ""))
        assertFalse(OnboardingRules.canContinue(OnboardingStep.Profile, simklActive = false, name = "   "))
        assertTrue(OnboardingRules.canContinue(OnboardingStep.Profile, simklActive = false, name = "Ada"))
        assertTrue(OnboardingRules.canContinue(OnboardingStep.Profile, simklActive = true, name = ""))
        assertTrue(OnboardingRules.canContinue(OnboardingStep.Look, simklActive = false, name = ""))
    }

    @Test
    fun simklIsInUseOnlyWhenConnectedWithAnAccountAndSwitchedOn() {
        assertTrue(OnboardingRules.simklActive(connected = true, hasAccount = true, useSimkl = true))
        assertFalse(OnboardingRules.simklActive(connected = true, hasAccount = true, useSimkl = false))
        assertFalse(OnboardingRules.simklActive(connected = true, hasAccount = false, useSimkl = true))
        assertFalse(OnboardingRules.simklActive(connected = false, hasAccount = true, useSimkl = true))
    }

    @Test
    fun theNameIsCutAtTwentyCharacters() {
        assertEquals("abcdefghijklmnopqrst", OnboardingRules.clampName("abcdefghijklmnopqrstuvwxyz"))
        assertEquals("Ada", OnboardingRules.clampName("Ada"))
    }

    @Test
    fun theMainButtonSaysWhatItDoes() {
        assertEquals("Get Started", OnboardingRules.primaryLabel(OnboardingStep.Welcome, anyKeyTyped = false, hasProxy = false))
        assertEquals("Start Watching", OnboardingRules.primaryLabel(OnboardingStep.Done, anyKeyTyped = false, hasProxy = false))
        assertEquals("Skip for now", OnboardingRules.primaryLabel(OnboardingStep.Keys, anyKeyTyped = false, hasProxy = false))
        assertEquals("Continue", OnboardingRules.primaryLabel(OnboardingStep.Keys, anyKeyTyped = false, hasProxy = true))
        assertEquals("Continue", OnboardingRules.primaryLabel(OnboardingStep.Keys, anyKeyTyped = true, hasProxy = false))
        assertEquals("Continue", OnboardingRules.primaryLabel(OnboardingStep.Look, anyKeyTyped = false, hasProxy = false))
    }

    @Test
    fun backAndSkipAreHiddenWhereTheyDoNotBelong() {
        assertFalse(OnboardingRules.showBack(OnboardingStep.Welcome))
        assertFalse(OnboardingRules.showBack(OnboardingStep.Done))
        assertTrue(OnboardingRules.showBack(OnboardingStep.Profile))
        assertFalse(OnboardingRules.showSkip(OnboardingStep.Done))
        assertTrue(OnboardingRules.showSkip(OnboardingStep.Welcome))
    }

    @Test
    fun phoneBelowTheBreakpointAndWideFromIt() {
        assertEquals(OnboardingLayout.Phone, OnboardingRules.layoutFor(390.dp))
        assertEquals(OnboardingLayout.Phone, OnboardingRules.layoutFor(639.dp))
        assertEquals(OnboardingLayout.Wide, OnboardingRules.layoutFor(640.dp))
        assertEquals(OnboardingLayout.Wide, OnboardingRules.layoutFor(1280.dp))
    }

    @Test
    fun theLayoutOverrideWinsOverTheWidth() {
        assertEquals(OnboardingLayout.Phone, OnboardingRules.layoutFor(1280.dp, LayoutOverride.Compact))
        assertEquals(OnboardingLayout.Wide, OnboardingRules.layoutFor(390.dp, LayoutOverride.Expanded))
    }

    @Test
    fun theStepRailOnlyFromNineHundred() {
        assertFalse(OnboardingRules.showsRail(899.dp))
        assertTrue(OnboardingRules.showsRail(900.dp))
        assertTrue(OnboardingRules.showsRail(1280.dp))
    }

    @Test
    fun theTypedNameBeatsTheSimklName() {
        val p = OnboardingRules.commitProfile(
            active = me,
            typedName = "  Ada ",
            simklActive = true,
            simklName = "Grace",
            accentHex = "#FF5C8D",
            systemAccent = false,
        )
        assertEquals("Ada", p.name)
        assertEquals(true, p.simklIdentity)
        assertEquals("#FF5C8D", p.colorHex)
    }

    @Test
    fun theSimklNameIsUsedWhenNothingIsTyped() {
        val p = OnboardingRules.commitProfile(me, "", simklActive = true, simklName = "Grace", accentHex = "#FF5C8D", systemAccent = false)
        assertEquals("Grace", p.name)
    }

    @Test
    fun withSimklOffTheNameAndTheFlagStayAsTheyWere() {
        val p = OnboardingRules.commitProfile(me, "", simklActive = false, simklName = "Grace", accentHex = "#FF5C8D", systemAccent = false)
        assertEquals("Me", p.name)
        assertNull(p.simklIdentity)
    }

    @Test
    fun theSystemAccentDoesNotChangeTheProfileColour() {
        val p = OnboardingRules.commitProfile(me, "Ada", simklActive = false, simklName = null, accentHex = "system", systemAccent = true)
        assertEquals("#7D5CFF", p.colorHex)
    }

    @Test
    fun theGreetingFallsBackToFriend() {
        assertEquals("friend", OnboardingRules.shownName("", simklActive = false, simklName = null))
        assertEquals("Grace", OnboardingRules.shownName("", simklActive = true, simklName = "Grace"))
        assertEquals("Ada", OnboardingRules.shownName("Ada", simklActive = false, simklName = "Grace"))
    }

    @Test
    fun everyCategoryHasALabelAndAnIcon() {
        ContentCategory.entries.forEach { c ->
            assertTrue(OnboardingRules.categoryTitle(c).isNotBlank(), c.name)
            assertTrue(OnboardingRules.categorySymbol(c).isNotBlank(), c.name)
        }
        assertEquals(3, ContentCategory.regions.size)
    }

    @Test
    fun catalogueTypesReadAsPeopleSayThem() {
        assertEquals("Movies", OnboardingRules.typeLabel("movie"))
        assertEquals("Series", OnboardingRules.typeLabel("series"))
        assertEquals("Channel", OnboardingRules.typeLabel("channel"))
    }
}
