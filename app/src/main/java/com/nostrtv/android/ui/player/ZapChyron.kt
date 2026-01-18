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

    if (recentZaps.isNotEmpty()) {
        val currentZap = recentZaps.getOrNull(currentIndex)

        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.7f)
                        )
                    )
                )
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            AnimatedContent(
                targetState = currentZap,
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
                if (zap != null) {
                    ZapItem(zap = zap)
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
                Color.Black.copy(alpha = 0.6f),
                RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Lightning emoji
        Text(
            text = "\u26A1",
            style = MaterialTheme.typography.titleLarge
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
                    .size(24.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        }

        // Sender name
        Text(
            text = zap.senderName ?: "Anonymous",
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = "zapped the stream",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.8f)
        )

        // Amount in sats with yellow/orange color
        Text(
            text = "${zap.formattedAmount} sats",
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            color = Color(0xFFFFA500) // Orange color for amount
        )

        // Message if present
        if (!zap.message.isNullOrBlank()) {
            Text(
                text = "and said:",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.8f)
            )
            Text(
                text = zap.message,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
