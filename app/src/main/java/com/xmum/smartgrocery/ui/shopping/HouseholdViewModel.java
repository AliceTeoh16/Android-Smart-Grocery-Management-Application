package com.xmum.smartgrocery.ui.shopping;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.ViewModelKt;

import com.xmum.smartgrocery.SmartGroceryApp;
import com.xmum.smartgrocery.data.local.dao.HouseholdMemberProfile;
import com.xmum.smartgrocery.data.local.entity.HouseholdEntity;
import com.xmum.smartgrocery.data.local.entity.HouseholdMemberStatus;
import com.xmum.smartgrocery.data.local.entity.HouseholdRole;
import com.xmum.smartgrocery.data.local.entity.HouseholdSettingEntity;
import com.xmum.smartgrocery.data.repository.HouseholdRepository;
import com.xmum.smartgrocery.data.sharing.CloudHousehold;
import com.xmum.smartgrocery.ui.pantrypal.PantryMemberUi;
import com.xmum.smartgrocery.utils.AndroidCoroutines;

import java.util.ArrayList;
import java.util.List;

import kotlin.Unit;
import kotlin.text.StringsKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.channels.BufferOverflow;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.FlowKt;
import kotlinx.coroutines.flow.MutableSharedFlow;
import kotlinx.coroutines.flow.SharedFlow;
import kotlinx.coroutines.flow.SharedFlowKt;
import kotlinx.coroutines.flow.SharingStarted;
import kotlinx.coroutines.flow.StateFlow;

public final class HouseholdViewModel extends AndroidViewModel {
    private final HouseholdRepository repository;
    private final CoroutineScope scope;
    private final MutableSharedFlow<String> outgoingMessages = SharedFlowKt.MutableSharedFlow(0, 0, BufferOverflow.SUSPEND);
    private final SharedFlow<String> messages = FlowKt.asSharedFlow(outgoingMessages);
    private final StateFlow<HouseholdUiState> state;

    public HouseholdViewModel(@NonNull Application application) {
        super(application);
        SmartGroceryApp app = (SmartGroceryApp) application;
        repository = new HouseholdRepository(app.getDatabase());
        scope = ViewModelKt.getViewModelScope(this);
        Flow<HouseholdUiState> households = FlowKt.combine(repository.getHousehold(), repository.getMembers(),
                repository.getSettings(), AndroidCoroutines.clock(60_000L),
                (home, members, settings, now, ignored) -> present(home, members, settings, now));
        Flow<HouseholdUiState> linked = FlowKt.combine(households, app.getDatabase().sharingDao().activeHousehold(),
                (home, cloud, ignored) -> link(home, cloud));
        state = FlowKt.stateIn(linked, scope, SharingStarted.Companion.WhileSubscribed(5000L, Long.MAX_VALUE),
                new HouseholdUiState());
    }

    @NonNull
    public StateFlow<HouseholdUiState> getState() {
        return state;
    }

    @NonNull
    public SharedFlow<String> getMessages() {
        return messages;
    }

    public void adjustWarning(int delta) {
        String id = state.getValue().getHouseholdId();
        if (id != null) {
            AndroidCoroutines.<Unit>launchWithMessage(scope, outgoingMessages,
                    completion -> repository.adjustWarningDays(id, delta, completion),
                    unused -> null, "Unable to save setting.");
        }
    }

    private static HouseholdUiState present(HouseholdEntity home, List<HouseholdMemberProfile> members,
            HouseholdSettingEntity settings, long now) {
        if (home == null) {
            return new HouseholdUiState();
        }
        String id = home.getHouseholdId();
        List<PantryMemberUi> memberRows = new ArrayList<>();
        boolean isOwner = false;
        for (HouseholdMemberProfile member : members) {
            if (!id.equals(member.getHouseholdId())) {
                continue;
            }
            String name = member.getDisplayName();
            memberRows.add(new PantryMemberUi(StringsKt.isBlank(name) ? member.getEmail() : name,
                    member.getRole(), memberDetail(member, now), initials(name), member.getUserId(), member.getStatus()));
            if (member.isCurrentUser() && HouseholdRole.OWNER.equals(member.getRole())
                    && HouseholdMemberStatus.ACTIVE.equals(member.getStatus())) {
                isOwner = true;
            }
        }
        int warningDays = settings != null && id.equals(settings.getHouseholdId()) ? settings.getExpiryWarningDays() : 3;
        return new HouseholdUiState(id, home.getName(), home.getDescription(), memberRows,
                home.getJoinCode(), warningDays, isOwner, null);
    }

    private static HouseholdUiState link(HouseholdUiState state, CloudHousehold linked) {
        String url = linked != null && linked.getLocalHouseholdId().equals(state.getHouseholdId())
                ? linked.getServerUrl() : null;
        return state.copy(state.getHouseholdId(), state.getName(), state.getDescription(), state.getMembers(),
                state.getInviteCode(), state.getExpiryWarningDays(), state.isOwner(), url);
    }

    private static String memberDetail(HouseholdMemberProfile member, long now) {
        if (HouseholdMemberStatus.INVITED.equals(member.getStatus())) {
            return "Invited · awaiting acceptance";
        }
        if (member.isCurrentUser()) {
            return "You · Active now";
        }
        Long lastActive = member.getLastSeenAt();
        if (lastActive == null) {
            return "Not active yet";
        }
        long minutes = Math.max(0L, now - lastActive) / 60_000L;
        if (minutes < 1) {
            return "Last active just now";
        }
        if (minutes < 60) {
            return "Last active " + minutes + "m ago";
        }
        if (minutes < 1440) {
            return "Last active " + minutes / 60 + "h ago";
        }
        return "Last active " + minutes / 1440 + "d ago";
    }

    private static String initials(String value) {
        StringBuilder result = new StringBuilder(2);
        for (String token : value.split(" ")) {
            if (!StringsKt.isBlank(token)) {
                result.append(Character.toUpperCase(token.charAt(0)));
                if (result.length() == 2) {
                    break;
                }
            }
        }
        return StringsKt.isBlank(result) ? "?" : result.toString();
    }
}
