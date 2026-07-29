package com.starlink.scanner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import com.starlink.scanner.ui.DishConnection
import com.starlink.scanner.ui.theme.LocalAppColors

/**
 * Persistent status strip shown under the top app bar on every screen:
 * dish connection state on the left, pending-upload count on the right.
 */
@Composable
fun StatusStrip(
    dishConnection: DishConnection,
    pendingCount: Int,
    modifier: Modifier = Modifier,
) {
    val appColors = LocalAppColors.current

    val (dotColor, dishText) = when (dishConnection) {
        DishConnection.CONNECTED -> appColors.success to "Dish connected"
        DishConnection.SEARCHING -> appColors.pending to "Searching…"
        DishConnection.NONE -> Color.Gray to "No dish"
    }

    val (pendingColor, pendingText) = if (pendingCount > 0) {
        appColors.pending to "$pendingCount pending"
    } else {
        appColors.success to "All synced"
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Text(
                    text = "  $dishText",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = pendingText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = pendingColor,
            )
        }
    }
}
