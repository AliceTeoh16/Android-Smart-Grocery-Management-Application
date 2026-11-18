package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small, original outline vectors matching the reference; no extended icon dependency. */
internal object PantryIcons {
    val Trash = icon("Delete") {
        moveTo(3f, 6f); lineTo(21f, 6f)
        moveTo(8f, 6f); lineTo(8f, 3f); lineTo(16f, 3f); lineTo(16f, 6f)
        moveTo(5f, 6f); lineTo(6f, 22f); lineTo(18f, 22f); lineTo(19f, 6f)
        moveTo(10f, 10f); lineTo(10f, 18f); moveTo(14f, 10f); lineTo(14f, 18f)
    }
    val Camera = icon("Camera") {
        moveTo(3f, 6f); lineTo(7f, 6f); lineTo(9f, 3f); lineTo(15f, 3f); lineTo(17f, 6f)
        lineTo(21f, 6f); lineTo(21f, 21f); lineTo(3f, 21f); close()
        moveTo(16f, 13f); arcTo(4f, 4f, 0f, true, true, 8f, 13f)
        arcTo(4f, 4f, 0f, true, true, 16f, 13f); close()
    }
    val Photo = icon("Photo") {
        moveTo(3f, 3f); lineTo(21f, 3f); lineTo(21f, 21f); lineTo(3f, 21f); close()
        moveTo(3f, 17f); lineTo(9f, 11f); lineTo(14f, 16f); lineTo(17f, 13f); lineTo(21f, 17f)
        moveTo(17f, 7f); lineTo(17.1f, 7f)
    }
    val Search = icon("Search") {
        moveTo(17f, 10f); arcTo(7f, 7f, 0f, true, true, 3f, 10f)
        arcTo(7f, 7f, 0f, true, true, 17f, 10f); close()
        moveTo(15f, 15f); lineTo(22f, 22f)
    }
    val Microphone = icon("Voice input") {
        moveTo(8f, 5f); curveTo(8f, 0f, 16f, 0f, 16f, 5f)
        lineTo(16f, 12f); curveTo(16f, 17f, 8f, 17f, 8f, 12f); close()
        moveTo(5f, 10f); lineTo(5f, 12f); curveTo(5f, 22f, 19f, 22f, 19f, 12f); lineTo(19f, 10f)
        moveTo(12f, 20f); lineTo(12f, 23f); moveTo(8f, 23f); lineTo(16f, 23f)
    }
    val Plus = icon("Add") {
        moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f)
    }
    val ArrowUp = icon("Submit") {
        moveTo(12f, 20f); lineTo(12f, 4f); moveTo(6f, 10f); lineTo(12f, 4f); lineTo(18f, 10f)
    }
    val Globe = icon("Language") {
        moveTo(22f, 12f); arcTo(10f, 10f, 0f, true, true, 2f, 12f)
        arcTo(10f, 10f, 0f, true, true, 22f, 12f); close()
        moveTo(2f, 12f); lineTo(22f, 12f)
        moveTo(12f, 2f); curveTo(6f, 7f, 6f, 17f, 12f, 22f)
        curveTo(18f, 17f, 18f, 7f, 12f, 2f)
    }
    val Shield = icon("Privacy") {
        moveTo(12f, 2f); lineTo(21f, 6f); lineTo(20f, 15f)
        quadTo(18f, 20f, 12f, 23f); quadTo(6f, 20f, 4f, 15f)
        lineTo(3f, 6f); close()
        moveTo(8f, 12f); lineTo(11f, 15f); lineTo(16f, 9f)
    }
    val Logout = icon("Sign out") {
        moveTo(10f, 3f); lineTo(4f, 3f); lineTo(4f, 21f); lineTo(10f, 21f)
        moveTo(9f, 12f); lineTo(22f, 12f); moveTo(17f, 7f); lineTo(22f, 12f); lineTo(17f, 17f)
    }
    val ChevronDown = icon("Expand") {
        moveTo(7f, 9f); lineTo(12f, 14f); lineTo(17f, 9f)
    }
    val Close = icon("Close") {
        moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f)
    }
    val Copy = icon("Copy") {
        moveTo(8f, 7f); lineTo(20f, 7f); lineTo(20f, 21f); lineTo(8f, 21f); close()
        moveTo(16f, 3f); lineTo(4f, 3f); lineTo(4f, 17f)
    }
    val Calendar = icon("Calendar") {
        moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 22f); lineTo(3f, 22f); close()
        moveTo(3f, 10f); lineTo(21f, 10f)
        moveTo(7f, 2f); lineTo(7f, 7f); moveTo(17f, 2f); lineTo(17f, 7f)
        moveTo(7f, 14f); lineTo(7.1f, 14f); moveTo(12f, 14f); lineTo(12.1f, 14f)
        moveTo(17f, 14f); lineTo(17.1f, 14f); moveTo(7f, 18f); lineTo(7.1f, 18f)
        moveTo(12f, 18f); lineTo(12.1f, 18f)
    }
    val Barcode = icon("Barcode") {
        moveTo(7f, 2f); lineTo(2f, 2f); lineTo(2f, 7f)
        moveTo(17f, 2f); lineTo(22f, 2f); lineTo(22f, 7f)
        moveTo(2f, 17f); lineTo(2f, 22f); lineTo(7f, 22f)
        moveTo(22f, 17f); lineTo(22f, 22f); lineTo(17f, 22f)
        moveTo(6f, 7f); lineTo(6f, 17f); moveTo(10f, 6f); lineTo(10f, 18f)
        moveTo(15f, 6f); lineTo(15f, 18f); moveTo(19f, 7f); lineTo(19f, 17f)
    }
    val Bell = icon("Expiry warning") {
        moveTo(5f, 17f); lineTo(19f, 17f); lineTo(17f, 13f); lineTo(17f, 8f)
        curveTo(17f, 1f, 7f, 1f, 7f, 8f); lineTo(7f, 13f); close()
        moveTo(10f, 21f); quadTo(12f, 23f, 14f, 21f)
        moveTo(3f, 5f); lineTo(1f, 10f); moveTo(21f, 5f); lineTo(23f, 10f)
    }
    val History = icon("History") {
        moveTo(22f, 12f); arcTo(10f, 10f, 0f, true, true, 2f, 12f)
        arcTo(10f, 10f, 0f, true, true, 22f, 12f); close()
        moveTo(12f, 5f); lineTo(12f, 13f); lineTo(18f, 16f)
    }
    private fun icon(name: String, block: PathBuilder.() -> Unit) = ImageVector.Builder(
        name, 24.dp, 24.dp, 24f, 24f,
    ).apply {
        path(
            fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            pathFillType = PathFillType.NonZero, pathBuilder = block,
        )
    }.build()

    val ArrowRight = icon("Arrow right") {
        moveTo(5f, 12f); lineTo(19f, 12f)
        moveTo(13f, 6f); lineTo(19f, 12f); lineTo(13f, 18f)
    }
    val ArrowLeft = icon("Arrow left") {
        moveTo(19f, 12f); lineTo(5f, 12f)
        moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f)
    }
    val Sprout = icon("Sprout") {
        moveTo(4f, 22f); lineTo(20f, 22f)
        moveTo(12f, 22f); lineTo(12f, 12f)
        moveTo(12f, 16f); curveTo(3f, 17f, 2f, 10f, 3f, 8f)
        curveTo(9f, 7f, 13f, 10f, 12f, 16f)
        moveTo(12f, 12f); curveTo(11f, 5f, 15f, 2f, 21f, 3f)
        curveTo(22f, 9f, 18f, 12f, 12f, 12f)
    }
    val Mail = icon("Email") {
        moveTo(4f, 5f); lineTo(20f, 5f); quadTo(22f, 5f, 22f, 7f)
        lineTo(22f, 19f); quadTo(22f, 21f, 20f, 21f)
        lineTo(4f, 21f); quadTo(2f, 21f, 2f, 19f)
        lineTo(2f, 7f); quadTo(2f, 5f, 4f, 5f); close()
        moveTo(2f, 7f); lineTo(11f, 13f); quadTo(12f, 14f, 13f, 13f); lineTo(22f, 7f)
    }
    val Person = icon("Person") {
        moveTo(16f, 8f); arcTo(4f, 4f, 0f, true, true, 8f, 8f)
        arcTo(4f, 4f, 0f, true, true, 16f, 8f); close()
        moveTo(5f, 21f); curveTo(5f, 11f, 19f, 11f, 19f, 21f)
    }
    val People = icon("Household") {
        moveTo(13f, 7f); arcTo(3.5f, 3.5f, 0f, true, true, 6f, 7f)
        arcTo(3.5f, 3.5f, 0f, true, true, 13f, 7f); close()
        moveTo(2f, 22f); lineTo(2f, 19f); curveTo(2f, 13f, 17f, 13f, 17f, 19f); lineTo(17f, 22f)
        moveTo(17f, 4f); curveTo(22f, 4f, 22f, 11f, 17f, 11f)
        moveTo(20f, 15f); quadTo(23f, 16f, 23f, 21f)
    }
    val Eye = icon("Visible password") {
        moveTo(2f, 12f); curveTo(7f, 3f, 17f, 3f, 22f, 12f)
        curveTo(17f, 21f, 7f, 21f, 2f, 12f); close()
        moveTo(15f, 12f); arcTo(3f, 3f, 0f, true, true, 9f, 12f)
        arcTo(3f, 3f, 0f, true, true, 15f, 12f); close()
    }
    val EyeOff = icon("Hidden password") {
        moveTo(3f, 3f); lineTo(21f, 21f)
        moveTo(10f, 5.5f); curveTo(15f, 4f, 20f, 8f, 22f, 12f)
        quadTo(21f, 15f, 19f, 16f)
        moveTo(6f, 6f); quadTo(3f, 8f, 2f, 12f)
        curveTo(6f, 19f, 12f, 21f, 17f, 18f)
        moveTo(9f, 10f); curveTo(7f, 14f, 11f, 17f, 14f, 14f)
    }
    val Check = icon("Check") {
        moveTo(5f, 12f); lineTo(10f, 17f); lineTo(20f, 7f)
    }
    val Help = icon("Help") {
        moveTo(22f, 12f); arcTo(10f, 10f, 0f, true, true, 2f, 12f)
        arcTo(10f, 10f, 0f, true, true, 22f, 12f); close()
        moveTo(9f, 8f); curveTo(9f, 4f, 16f, 5f, 15f, 9f)
        curveTo(15f, 11f, 12f, 11f, 12f, 14f)
        moveTo(12f, 18f); lineTo(12.01f, 18f)
    }
    val Fridge = icon("Inventory") {
        moveTo(7f, 2f); lineTo(17f, 2f); quadTo(19f, 2f, 19f, 5f)
        lineTo(19f, 21f); lineTo(5f, 21f); lineTo(5f, 5f)
        quadTo(5f, 2f, 7f, 2f); close()
        moveTo(5f, 9f); lineTo(19f, 9f)
        moveTo(14f, 5f); lineTo(14f, 7f)
        moveTo(14f, 12f); lineTo(14f, 15f)
    }
    val Chef = icon("Plan a meal") {
        moveTo(6f, 15f); curveTo(0f, 13f, 2f, 5f, 7f, 6f)
        curveTo(8f, 1f, 16f, 1f, 17f, 6f)
        curveTo(23f, 5f, 24f, 13f, 18f, 15f)
        lineTo(18f, 21f); lineTo(6f, 21f); close()
        moveTo(6f, 17f); lineTo(18f, 17f)
    }
    val Wallet = icon("Wallet") {
        moveTo(20f, 7f); lineTo(4f, 7f); quadTo(2f, 7f, 2f, 5f)
        quadTo(2f, 3f, 4f, 3f); lineTo(18f, 3f); lineTo(18f, 7f)
        moveTo(2f, 5f); lineTo(2f, 20f); lineTo(20f, 20f); lineTo(20f, 7f)
        moveTo(21f, 12f); lineTo(16f, 12f); quadTo(13f, 15f, 16f, 17f)
        lineTo(21f, 17f); close()
        moveTo(17f, 14.5f); lineTo(17.01f, 14.5f)
    }
    val Home = icon("Home") {
        moveTo(3f, 10f); lineTo(12f, 2f); lineTo(21f, 10f)
        lineTo(21f, 22f); lineTo(3f, 22f); close()
        moveTo(9f, 22f); lineTo(9f, 13f); lineTo(15f, 13f); lineTo(15f, 22f)
    }
    val Sun = icon("Morning") {
        moveTo(16f, 12f); arcTo(4f, 4f, 0f, true, true, 8f, 12f)
        arcTo(4f, 4f, 0f, true, true, 16f, 12f); close()
        moveTo(12f, 1f); lineTo(12f, 4f); moveTo(12f, 20f); lineTo(12f, 23f)
        moveTo(1f, 12f); lineTo(4f, 12f); moveTo(20f, 12f); lineTo(23f, 12f)
        moveTo(4f, 4f); lineTo(6f, 6f); moveTo(18f, 18f); lineTo(20f, 20f)
        moveTo(4f, 20f); lineTo(6f, 18f); moveTo(18f, 6f); lineTo(20f, 4f)
    }
    val Share = icon("Share") {
        moveTo(7f, 10f); lineTo(17f, 5f); moveTo(7f, 14f); lineTo(17f, 19f)
        moveTo(8f, 12f); arcTo(3f, 3f, 0f, true, true, 2f, 12f)
        arcTo(3f, 3f, 0f, true, true, 8f, 12f); close()
        moveTo(22f, 4f); arcTo(3f, 3f, 0f, true, true, 16f, 4f)
        arcTo(3f, 3f, 0f, true, true, 22f, 4f); close()
        moveTo(22f, 20f); arcTo(3f, 3f, 0f, true, true, 16f, 20f)
        arcTo(3f, 3f, 0f, true, true, 22f, 20f); close()
    }
    val Leaf = icon("Leaf") {
        moveTo(3f, 22f); quadTo(4f, 12f, 14f, 11f)
        moveTo(6f, 17f); curveTo(-1f, 5f, 13f, 8f, 20f, 2f)
        curveTo(27f, 20f, 16f, 25f, 6f, 17f); close()
    }
    val Box = icon("Low stock") {
        moveTo(2f, 6f); lineTo(12f, 2f); lineTo(22f, 6f); lineTo(12f, 11f); close()
        moveTo(2f, 6f); lineTo(2f, 18f); lineTo(12f, 23f); lineTo(22f, 18f); lineTo(22f, 6f)
        moveTo(12f, 11f); lineTo(12f, 23f)
        moveTo(7f, 4f); lineTo(17f, 9f); lineTo(17f, 14f)
        moveTo(3f, 12f); lineTo(7f, 14f)
    }
    val Sparkles = icon("AI checklist") {
        moveTo(10f, 2f); lineTo(12f, 9f); lineTo(19f, 11f)
        lineTo(12f, 13f); lineTo(10f, 21f); lineTo(8f, 13f)
        lineTo(1f, 11f); lineTo(8f, 9f); close()
        moveTo(20f, 1f); lineTo(20f, 6f); moveTo(17.5f, 3.5f); lineTo(22.5f, 3.5f)
        moveTo(3f, 18f); lineTo(3f, 23f); moveTo(0.5f, 20.5f); lineTo(5.5f, 20.5f)
    }
    val Basket = icon("Shopping") {
        moveTo(2f, 10f); lineTo(22f, 10f)
        moveTo(4f, 10f); lineTo(7f, 21f); lineTo(17f, 21f); lineTo(20f, 10f)
        moveTo(5f, 10f); lineTo(10f, 3f); moveTo(19f, 10f); lineTo(14f, 3f)
        moveTo(9f, 13f); lineTo(10f, 18f); moveTo(15f, 13f); lineTo(14f, 18f)
        moveTo(6f, 16f); lineTo(18f, 16f)
    }
    val Pie = icon("Budget") {
        moveTo(11f, 2f); arcTo(10f, 10f, 0f, true, false, 22f, 13f)
        lineTo(11f, 13f); close()
        moveTo(15f, 1f); lineTo(15f, 9f); lineTo(23f, 9f)
        arcTo(9f, 9f, 0f, false, false, 15f, 1f); close()
    }
    val Milk = icon("Milk") {
        moveTo(9f, 2f); lineTo(15f, 2f); lineTo(15f, 6f)
        lineTo(17f, 10f); lineTo(17f, 21f); lineTo(7f, 21f)
        lineTo(7f, 10f); lineTo(9f, 6f); close()
        moveTo(7f, 12f); quadTo(10f, 10f, 12f, 12f); quadTo(15f, 14f, 17f, 12f)
    }
}
