package com.nexura.messenger.data

/**
 * Membership policy for encrypted conversations.
 *
 * Ordinary groups must contain at least two distinct members. The only allowed
 * one-member conversation is the server-resolved Saved Messages chat, and its
 * sole member must be the currently authenticated account.
 */
internal fun isValidConversationMembership(
    conversationId: String,
    memberIds: Collection<String>,
    currentUserId: String,
    savedConversationId: String,
    maxRecipients: Int
): Boolean {
    val members = memberIds.map { it.trim() }
    if (members.any { it.isEmpty() } || members.size != members.distinct().size) return false
    if (conversationId.equals(savedConversationId, ignoreCase = true)) {
        return members.size == 1 && members.singleOrNull()
            ?.equals(currentUserId, ignoreCase = true) == true
    }
    return members.size in 2..maxRecipients
}
