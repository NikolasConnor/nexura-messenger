package com.nexura.messenger.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedConversationMembershipPolicyTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val other = "22222222-2222-4222-8222-222222222222"
    private val saved = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val group = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    @Test
    fun acceptsOnlyTheOwnerInSavedMessages() {
        assertTrue(isValidConversationMembership(saved, listOf(owner), owner, saved, 64))
    }

    @Test
    fun rejectsOtherUsersInSavedMessages() {
        assertFalse(isValidConversationMembership(saved, listOf(other), owner, saved, 64))
        assertFalse(isValidConversationMembership(saved, listOf(owner, other), owner, saved, 64))
    }

    @Test
    fun rejectsOneMemberOrdinaryGroups() {
        assertFalse(isValidConversationMembership(group, listOf(owner), owner, saved, 64))
    }

    @Test
    fun rejectsDuplicateMemberIds() {
        assertFalse(isValidConversationMembership(group, listOf(owner, owner), owner, saved, 64))
    }

    @Test
    fun acceptsOrdinaryGroupsWithTwoOrMoreMembers() {
        assertTrue(isValidConversationMembership(group, listOf(owner, other), owner, saved, 64))
    }

    @Test
    fun rejectsGroupsAboveRecipientLimit() {
        val members = (1..65).map { "user-$it" }
        assertFalse(isValidConversationMembership(group, members, owner, saved, 64))
    }
}
