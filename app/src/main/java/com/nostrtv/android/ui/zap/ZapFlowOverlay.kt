package com.nostrtv.android.ui.zap

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nostrtv.android.data.zap.ZapAmount
import com.nostrtv.android.data.zap.ZapManager
import com.nostrtv.android.util.QRCodeUtils
import kotlinx.coroutines.delay

/**
 * Zap flow state machine
 */
sealed class ZapFlowState {
    object Hidden : ZapFlowState()
    object SelectAmount : ZapFlowState()
    data class Loading(val amountSats: Long) : ZapFlowState()
    data class ShowQR(val invoice: String, val amountSats: Long) : ZapFlowState()
    data class Confirmed(val amountSats: Long) : ZapFlowState()
    data class Error(val message: String) : ZapFlowState()
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ZapFlowOverlay(
    state: ZapFlowState,
    streamerName: String,
    onAmountSelected: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state == ZapFlowState.Hidden) return

    // Auto-dismiss confirmation after 2 seconds
    LaunchedEffect(state) {
        if (state is ZapFlowState.Confirmed) {
            delay(2000)
            onDismiss()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyUp && keyEvent.key == Key.Back) {
                    onDismiss()
                    true
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(200))
            },
            label = "zap_flow_transition"
        ) { currentState ->
            when (currentState) {
                is ZapFlowState.SelectAmount -> {
                    AmountSelectionContent(
                        streamerName = streamerName,
                        onAmountSelected = onAmountSelected,
                        onCancel = onDismiss
                    )
                }
                is ZapFlowState.Loading -> {
                    LoadingContent(amountSats = currentState.amountSats)
                }
                is ZapFlowState.ShowQR -> {
                    QRCodeContent(
                        invoice = currentState.invoice,
                        amountSats = currentState.amountSats,
                        onCancel = onDismiss
                    )
                }
                is ZapFlowState.Confirmed -> {
                    ConfirmationContent(amountSats = currentState.amountSats)
                }
                is ZapFlowState.Error -> {
                    ErrorContent(
                        message = currentState.message,
                        onDismiss = onDismiss
                    )
                }
                ZapFlowState.Hidden -> { /* Not shown */ }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AmountSelectionContent(
    streamerName: String,
    onAmountSelected: (Long) -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .background(
                Color(0xFF1a1a1a),
                RoundedCornerShape(16.dp)
            )
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Lightning bolt icon
        Text(
            text = "\u26A1",
            style = MaterialTheme.typography.displaySmall,
            color = Color(0xFFFFA500)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Zap $streamerName",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Amount buttons in 2 rows of 3
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ZapManager.PRESET_AMOUNTS.take(3).forEach { amount ->
                    ZapAmountChip(
                        amount = amount,
                        onClick = { onAmountSelected(amount.sats) }
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ZapManager.PRESET_AMOUNTS.drop(3).forEach { amount ->
                    ZapAmountChip(
                        amount = amount,
                        onClick = { onAmountSelected(amount.sats) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onCancel) {
            Text("Cancel")
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ZapAmountChip(
    amount: ZapAmount,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(100.dp)
            .height(56.dp),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(12.dp)
        ),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFFFFA500).copy(alpha = 0.15f),
            focusedContainerColor = Color(0xFFFFA500).copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = amount.emoji,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = amount.label,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LoadingContent(amountSats: Long) {
    Column(
        modifier = Modifier
            .background(
                Color(0xFF1a1a1a),
                RoundedCornerShape(16.dp)
            )
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "\u26A1",
            style = MaterialTheme.typography.displayMedium,
            color = Color(0xFFFFA500)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Preparing $amountSats sat zap...",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QRCodeContent(
    invoice: String,
    amountSats: Long,
    onCancel: () -> Unit
) {
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(invoice) {
        qrBitmap = QRCodeUtils.generateQRCodeAsync(invoice.uppercase(), 350)
    }

    Column(
        modifier = Modifier
            .background(
                Color(0xFF1a1a1a),
                RoundedCornerShape(16.dp)
            )
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Scan to Zap",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "$amountSats sats",
            style = MaterialTheme.typography.titleLarge,
            color = Color(0xFFFFA500)
        )

        Spacer(modifier = Modifier.height(20.dp))

        qrBitmap?.let { bitmap ->
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Lightning Invoice QR Code",
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Scan with your Lightning wallet",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Waiting for payment...",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFFFA500)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(onClick = onCancel) {
            Text("Cancel")
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ConfirmationContent(amountSats: Long) {
    Column(
        modifier = Modifier
            .background(
                Color(0xFF1a1a1a),
                RoundedCornerShape(16.dp)
            )
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Checkmark with lightning
        Text(
            text = "\u26A1",
            style = MaterialTheme.typography.displayLarge,
            color = Color(0xFFFFA500)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Zap Sent!",
            style = MaterialTheme.typography.headlineMedium,
            color = Color(0xFF4CAF50)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "$amountSats sats",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ErrorContent(
    message: String,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .background(
                Color(0xFF1a1a1a),
                RoundedCornerShape(16.dp)
            )
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Error",
            style = MaterialTheme.typography.headlineSmall,
            color = Color(0xFFFF5252)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onDismiss) {
            Text("OK")
        }
    }
}
