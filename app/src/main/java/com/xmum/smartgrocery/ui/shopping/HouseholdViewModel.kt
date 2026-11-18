package com.xmum.smartgrocery.ui.shopping

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xmum.smartgrocery.SmartGroceryApp
import com.xmum.smartgrocery.data.local.entity.HouseholdRole
import com.xmum.smartgrocery.data.local.entity.HouseholdMemberStatus
import com.xmum.smartgrocery.data.repository.HouseholdRepository
import com.xmum.smartgrocery.ui.pantrypal.PantryMemberUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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

class HouseholdViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SmartGroceryApp
    private val repository = HouseholdRepository(app.database)
    private val clock = flow { while (true) { emit(System.currentTimeMillis()); delay(60_000) } }
    private val _messages = MutableSharedFlow<String>()
    val messages = _messages.asSharedFlow()

    val state = combine(repository.household, repository.members, repository.settings, clock) { home, members, settings, now ->
        if (home == null) HouseholdUiState()
        else HouseholdUiState(home.householdId, home.name, home.description,
            members.filter { it.householdId == home.householdId }.map { member ->
                val detail = when {
                    member.status == HouseholdMemberStatus.INVITED -> "Invited · awaiting acceptance"
                    member.isCurrentUser -> "You · Active now"
                    member.lastSeenAt == null -> "Not active yet"
                    else -> {
                        val minutes = ((now - member.lastSeenAt).coerceAtLeast(0) / 60_000)
                        when {
                            minutes < 1 -> "Last active just now"
                            minutes < 60 -> "Last active ${minutes}m ago"
                            minutes < 1440 -> "Last active ${minutes / 60}h ago"
                            else -> "Last active ${minutes / 1440}d ago"
                        }
                    }
                }
                PantryMemberUi(member.displayName.ifBlank { member.email }, member.role, detail,
                    member.displayName.split(' ').filter(String::isNotBlank).take(2)
                        .joinToString("") { it.first().uppercaseChar().toString() }.ifBlank { "?" }, member.userId, member.status)
            }, home.joinCode, settings?.takeIf { it.householdId == home.householdId }?.expiryWarningDays ?: 3,
            members.any { it.householdId == home.householdId && it.isCurrentUser && it.role == HouseholdRole.OWNER && it.status == HouseholdMemberStatus.ACTIVE })
    }.combine(app.database.sharingDao().activeHousehold()) { state, link -> state.copy(sharingServer = link?.takeIf { it.localHouseholdId == state.householdId }?.serverUrl) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HouseholdUiState())

    fun adjustWarning(delta: Int) {
        val id = state.value.householdId ?: return
        viewModelScope.launch {
            runCatching { repository.adjustWarningDays(id, delta) }.onFailure { _messages.emit(it.message ?: "Unable to save setting.") }
        }
    }
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
