package com.xmum.smartgrocery.ui.pantrypal

import android.text.Editable
import android.text.TextWatcher
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.viewinterop.AndroidView
import android.view.View
import android.view.ViewGroup

@Composable
internal fun VoiceCommandCard(subtitle: String, onVoice: () -> Unit) {
    Surface(onClick = onVoice, shape = RoundedCornerShape(16.dp), color = PantryColors.Pastel) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).background(PantryColors.Green, CircleShape), contentAlignment = Alignment.Center) {
                Icon(PantryIcons.Microphone, null, Modifier.size(19.dp), tint = Color.White)
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("Voice command", color = PantryColors.Green, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = PantryColors.Muted, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
    }
}

/** One scrolling page, shared by the feature screens. Values still come from their existing hosts. */
@Composable
internal fun FeatureScrollPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium.copy(
        lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified)) {
        Surface(modifier.fillMaxSize(), color = PantryColors.Cream, contentColor = PantryColors.Ink) {
            Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 100.dp), content = content)
        }
    }
}

@Composable
internal fun FeatureTopBar(title: String, onHome: () -> Unit, initials: String,
    onProfile: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onHome, modifier = Modifier.size(28.dp)) {
            Icon(PantryIcons.Home, "Home", Modifier.size(21.dp), tint = PantryColors.Green)
        }
        Spacer(Modifier.width(6.dp))
        Text(title, Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.Bold, color = PantryColors.Ink)
        if (trailing != null) trailing() else {
            Surface(onClick = onProfile, shape = CircleShape, color = PantryColors.Pastel) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Text(initials.ifBlank { "?" }, color = PantryColors.Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Rehost existing view controls so their listeners, adapters and animations keep their owners. */
@Composable
internal fun BoundLegacyView(view: View, modifier: Modifier = Modifier) {
    AndroidView(factory = {
        (view.parent as? ViewGroup)?.removeView(view)
        view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (view is androidx.recyclerview.widget.RecyclerView) {
            view.isNestedScrollingEnabled = false
            view.setPadding(0, 0, 0, 0)
        }
        view
    }, modifier = modifier.fillMaxWidth())
}

/** Observes the existing view binding, including changes made by its original listeners. */
@Composable
internal fun rememberBoundText(view: TextView): State<String> {
    val text = remember(view) { mutableStateOf(view.text.toString()) }
    DisposableEffect(view) {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                text.value = s?.toString().orEmpty()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        view.addTextChangedListener(watcher)
        text.value = view.text.toString()
        onDispose { view.removeTextChangedListener(watcher) }
    }
    return text
}

@Composable
internal fun FeatureHeader(title: String, onBack: () -> Unit, initials: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
            Icon(PantryIcons.ArrowLeft, "Back", tint = PantryColors.Green, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(2.dp))
        Text(title, Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.Bold, color = PantryColors.Ink)
        if (!initials.isNullOrBlank()) {
            Box(Modifier.size(36.dp).background(PantryColors.Pastel, CircleShape), contentAlignment = Alignment.Center) {
                Text(initials, color = PantryColors.Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun FeatureCard(modifier: Modifier = Modifier, color: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = color,
        shadowElevation = 2.dp) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
internal fun FeatureAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    icon: ImageVector? = null, color: Color = PantryColors.Orange, contentColor: Color = Color.White) {
    Button(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = contentColor)) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun FeatureBadge(text: String, background: Color = PantryColors.Pastel, foreground: Color = PantryColors.Green) {
    Text(text, modifier = Modifier.background(background, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
        color = foreground, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
}
