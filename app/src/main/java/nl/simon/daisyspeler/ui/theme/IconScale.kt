package nl.simon.daisyspeler.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Schaalverdeling voor iconen. 1f is normaal, [LARGER_ICON_SCALE] is vergroot. */
val LocalIconScale = compositionLocalOf { 1f }

const val LARGER_ICON_SCALE = 1.5f

/** Standaardgrootte van een icoon in bijvoorbeeld een IconButton. */
val DefaultIconSize: Dp = 24.dp

/** Schaalt een basisgrootte met de gekozen iconenschaal. */
@Composable
fun scaledIconSize(base: Dp): Dp = base * LocalIconScale.current
