package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.xmum.smartgrocery.R
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight

@Composable
fun WelcomeScreen(
    onNavigateToLogin: () -> Unit,
    onNavigateToSignUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PantryPage(modifier) {
        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(PantryColors.Green, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Icon(PantryIcons.Sprout, null, Modifier.size(25.dp), tint = Color.White)
            }
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.app_name), color = PantryColors.Green, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
        PantryIllustration()
        Spacer(Modifier.height(12.dp))
        Eyebrow("YOUR FRIENDLY PANTRY COMPANION")
        Spacer(Modifier.height(12.dp))
        Text("Good food. Less\nwaste. More together.", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(12.dp))
        Text("A happier pantry starts here. Keep track of what\nyou have, cook what you love, and spend\nmindfully.",
            color = PantryColors.Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            WelcomeBenefit(PantryIcons.Fridge, "Keep it fresh")
            WelcomeBenefit(PantryIcons.Chef, "Plan a meal")
            WelcomeBenefit(PantryIcons.Wallet, "Save more")
        }
        Spacer(Modifier.height(24.dp))
        PrimaryCta("Get started", onNavigateToSignUp)
        AccountPrompt("Already part of the pantry?", "Log in", onNavigateToLogin)
    }
}

@Composable
private fun WelcomeBenefit(icon: ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(24.dp), tint = PantryColors.Green)
        Spacer(Modifier.height(8.dp))
        Text(label, color = PantryColors.Green, style = MaterialTheme.typography.labelSmall)
    }
}

/** Original vector illustration in the reference's 364 × 276 coordinate space. */
@Composable
private fun PantryIllustration() {
    Box(Modifier.fillMaxWidth().aspectRatio(364f / 276f).background(PantryColors.Pastel, RoundedCornerShape(32.dp))) {
        Canvas(Modifier.fillMaxSize()) {
            withTransform({ scale(size.width / 364f, size.height / 276f, pivot = Offset.Zero) }) {
                drawCircle(Color(0xFFF3F7EA), 115f, Offset(180f, 137f))
                withTransform({ rotate(-8f, Offset(139f, 109f)) }) {
                    drawRoundRect(Color.White, Offset(110f, 67f), Size(53f, 97f), CornerRadius(12f))
                    drawRect(Color(0xFFE8E0F5), Offset(103f, 104f), Size(68f, 28f))
                }
                withTransform({ rotate(16f, Offset(167f, 126f)) }) {
                    drawRoundRect(Color(0xFFF3DA96), Offset(149f, 60f), Size(36f, 131f), CornerRadius(18f))
                }
                drawOval(PantryColors.Orange, Offset(215f, 108f), Size(55f, 64f))
                withTransform({ rotate(-28f, Offset(233f, 92f)) }) {
                    drawOval(PantryColors.Green, Offset(223f, 72f), Size(20f, 40f))
                }
                val basket = Path().apply {
                    moveTo(79f, 142f); lineTo(287f, 142f); lineTo(268f, 216f)
                    quadraticTo(264f, 233f, 248f, 233f); lineTo(119f, 233f)
                    quadraticTo(103f, 233f, 100f, 220f); close()
                }
                drawPath(basket, PantryColors.Green)
                listOf(120f, 151f, 182f, 213f, 244f).forEach { x ->
                    drawLine(Color(0xFF7E9E6E), Offset(x, 164f), Offset(x, 201f), 8f, StrokeCap.Round)
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 45.dp, bottom = 15.dp)
                .background(Color.White, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(PantryIcons.Check, null, Modifier.size(14.dp), tint = PantryColors.Green)
            Spacer(Modifier.width(6.dp))
            Text("A little less waste.", color = PantryColors.Green, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Preview(name = "Welcome · 412 × 892", widthDp = 412, heightDp = 892, showBackground = true)
@Composable
private fun WelcomeScreenPreview() = PantryPalTheme { WelcomeScreen({}, {}) }
