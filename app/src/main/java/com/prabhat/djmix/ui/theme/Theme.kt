package com.prabhat.djmix.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Design tokens for Compose screens; the same values as res/values/colors.xml, which the View console uses. */
object DJ {
    val ground = Color(0xFF0F1115)
    val surface = Color(0xFF171A1F)
    val control = Color(0xFF22262D)
    val controlDim = Color(0xFF1B1F25)
    val border = Color(0xFF30363F)
    val text = Color(0xFFE7E9ED)
    val muted = Color(0xFF8E96A1)
    val deckA = Color(0xFF37C6D6)
    val deckATint = Color(0xFF15262A)
    val deckB = Color(0xFFF5A524)
    val deckBTint = Color(0xFF2A2113)
    val loop = Color(0xFF45D483)
    val kill = Color(0xFFFF4D4D)
    val killTint = Color(0xFF2A1B1B)
}

/** Material 3 dark theme painted with the console's tokens, so dialogs, switches and buttons match it. */
@Composable
fun DJMixTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = DJ.deckA, onPrimary = DJ.ground,
        secondary = DJ.deckB, onSecondary = DJ.ground,
        background = DJ.ground, onBackground = DJ.text,
        surface = DJ.surface, onSurface = DJ.text,
        surfaceVariant = DJ.control, onSurfaceVariant = DJ.muted,
        surfaceContainerHigh = DJ.surface,
        outline = DJ.border, error = DJ.kill,
    ),
    content = content,
)
