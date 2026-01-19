package com.nostrtv.android.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nostrtv.android.data.nostr.ZapReceipt
import kotlinx.coroutines.delay

private const val ZAP_DISPLAY_DURATION_MS = 3000L

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ZapChyron(
    zapReceipts: List<ZapReceipt>,
    isAuthenticated: Boolean,
    onZapClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Show only the latest 10 zaps
    val recentZaps = remember(zapReceipts) {
        zapReceipts.take(10)
    }

    // Current index for cycling through zaps
    var currentIndex by remember { mutableIntStateOf(0) }

    // Cycle through zaps every 3 seconds
    LaunchedEffect(recentZaps) {
        if (recentZaps.isNotEmpty()) {
            currentIndex = 0
            while (true) {
                delay(ZAP_DISPLAY_DURATION_MS)
                if (recentZaps.isNotEmpty()) {
                    currentIndex = (currentIndex + 1) % recentZaps.size
                }
            }
        }
    }

    // Reset index when list changes significantly
    LaunchedEffect(recentZaps.size) {
        if (currentIndex >= recentZaps.size) {
            currentIndex = 0
        }
    }

    val currentZap = recentZaps.getOrNull(currentIndex)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(35.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.8f)
                    )
                )
            )
            .padding(horizontal = 12.dp)
    ) {
        // Zap ticker on the left
        if (recentZaps.isNotEmpty() && currentZap != null) {
            AnimatedContent(
                targetState = currentZap,
                modifier = Modifier.align(Alignment.CenterStart),
                transitionSpec = {
                    (slideInVertically(
                        initialOffsetY = { it },
                        animationSpec = tween(300)
                    ) + fadeIn(animationSpec = tween(300)))
                        .togetherWith(
                            slideOutVertically(
                                targetOffsetY = { -it },
                                animationSpec = tween(300)
                            ) + fadeOut(animationSpec = tween(300))
                        )
                },
                label = "zap_transition"
            ) { zap ->
                ZapItem(zap = zap)
            }
        }

        // Zap button on the right (only when authenticated)
        if (isAuthenticated) {
            Surface(
                onClick = onZapClick,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(28.dp),
                shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
                    shape = RoundedCornerShape(6.dp)
                ),
                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                    containerColor = Color(0xFFFFA500).copy(alpha = 0.2f),
                    focusedContainerColor = Color(0xFFFFA500).copy(alpha = 0.5f)
                )
            ) {
                Box(
                    modifier = Modifier.size(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "\u26A1",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFFFFA500)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ZapItem(
    zap: ZapReceipt,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(
                Color.Black.copy(alpha = 0.8f),
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Lightning emoji
        Text(
            text = "\u26A1",
            style = MaterialTheme.typography.labelMedium
        )

        // Profile picture
        if (!zap.senderPicture.isNullOrEmpty()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(zap.senderPicture)
                    .crossfade(true)
                    .build(),
                contentDescription = "Zapper avatar",
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        }

        // Sender name
        Text(
            text = zap.senderName ?: "Anonymous",
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold
            ),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = "zapped",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.7f)
        )

        // Amount in sats with yellow/orange color
        Text(
            text = "${zap.formattedAmount} sats",
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold
            ),
            color = Color(0xFFFFA500) // Orange color for amount
        )

        // Message if present
        if (!zap.message.isNullOrBlank()) {
            Text(
                text = "\"${zap.message}\"",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
