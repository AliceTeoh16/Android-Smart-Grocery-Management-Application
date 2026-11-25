package com.xmum.smartgrocery.ui.shopping

import com.xmum.smartgrocery.data.local.entity.HouseholdMemberStatus
import com.xmum.smartgrocery.ui.pantrypal.PantryMemberUi

data class HouseholdUiState(
    val householdId: String? = null,
    val name: String = "No household selected",
    val description: String = "Create or join a household to share your pantry, shopping list, and budget.",
    val members: List<PantryMemberUi> = emptyList(),
    val inviteCode: String = "",
    val expiryWarningDays: Int = 3,
    val isOwner: Boolean = false,
    val sharingServer: String? = null
) {
    val inviteLink: String get() = if (inviteCode.isEmpty()) "" else HouseholdInvite.link(inviteCode, sharingServer)
    val activeMemberCount: Int get() = members.count { it.status == HouseholdMemberStatus.ACTIVE }
}

internal object HouseholdInvite {
    fun link(code: String, server: String? = null): String = "smartgrocery://household/join?code=$code" +
        (server?.let { "&server=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
    fun shareIntent(name: String, code: String, server: String? = null) = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, "Join $name")
        putExtra(android.content.Intent.EXTRA_TEXT, "Join $name in Smart Grocery.\nCode: $code\n${link(code, server)}")
    }
}
