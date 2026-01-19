package com.nostrtv.android.ui.player

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nostrtv.android.data.nostr.ChatMessage
import com.nostrtv.android.data.nostr.LiveStream
import com.nostrtv.android.data.nostr.Profile
import com.nostrtv.android.viewmodel.PlayerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerScreen(
    streamId: String,
    onBack: () -> Unit,
    stream: LiveStream? = null,
    streamerProfile: Profile? = null,
    viewModel: PlayerViewModel = viewModel(
        factory = PlayerViewModel.Factory(LocalContext.current)
    )
) {
    val currentStream by viewModel.stream.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val zapReceipts by viewModel.zapReceipts.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    // Load stream data when provided
    LaunchedEffect(stream) {
        stream?.let {
            viewModel.loadStream(it)
            viewModel.publishPresence(true)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyUp) {
                    when (keyEvent.key) {
                        Key.Back, Key.Escape -> {
                            onBack()
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Video Area (83%)
            Column(
                modifier = Modifier
                    .weight(0.83f)
                    .fillMaxHeight()
            ) {
                // Stream Info Header (above video) - clickable
                StreamInfoHeader(
                    stream = currentStream,
                    streamerProfile = streamerProfile,
                    onClick = {
                        // TODO: Navigate to streamer profile
                        Log.d("PlayerScreen", "Stream info clicked: ${currentStream?.streamerPubkey}")
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Video Player (fills remaining space)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    val streamUrl = currentStream?.streamingUrl ?: ""

                    if (streamUrl.isNotEmpty()) {
                        VideoPlayer(
                            streamUrl = streamUrl,
                            modifier = Modifier.fillMaxSize(),
                            onError = { e ->
                                Log.e("PlayerScreen", "Video error: ${e.message}")
                            }
                        )
                    } else if (isLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Loading stream...",
                                color = Color.White
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No stream URL available",
                                color = Color.White
                            )
                        }
                    }
                }

                // Zap Chyron Footer (below video)
                ZapChyron(
                    zapReceipts = zapReceipts,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Chat Area (17%)
            ChatPanel(
                messages = chatMessages,
                onSendMessage = { message ->
                    viewModel.sendChatMessage(message)
                },
                modifier = Modifier
                    .weight(0.17f)
                    .fillMaxHeight()
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StreamInfoHeader(
    stream: LiveStream?,
    streamerProfile: Profile?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(0.dp)
        ),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color.Black.copy(alpha = 0.8f),
            focusedContainerColor = Color.Black.copy(alpha = 0.9f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(35.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            stream?.let { s ->
                // Streamer Avatar
                val profilePicture = streamerProfile?.picture
                if (!profilePicture.isNullOrEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(profilePicture)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Streamer avatar",
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    // Placeholder avatar
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        val initial = streamerProfile?.displayNameOrName?.firstOrNull()
                            ?: s.streamerName?.firstOrNull()
                            ?: '?'
                        Text(
                            text = initial.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }

                // Streamer name
                Text(
                    text = streamerProfile?.displayNameOrName ?: s.streamerName ?: "Unknown",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )

                // Stream title
                Text(
                    text = s.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )

                // Viewer count
                if (s.viewerCount > 0) {
                    Text(
                        text = "${s.viewerCount} viewers",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            } ?: run {
                Text(
                    text = "Loading...",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChatPanel(
    messages: List<ChatMessage>,
    onSendMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    var showMessages by remember { mutableStateOf(true) }
    var messageText by remember { mutableStateOf("") }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.9f))
    ) {
        // Chat Header with hide/show button
        ChatHeader(
            showMessages = showMessages,
            onToggleMessages = { showMessages = !showMessages },
            modifier = Modifier.fillMaxWidth()
        )

        // Chat Message List (hideable)
        if (showMessages) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                if (messages.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No messages yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        reverseLayout = true,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(messages, key = { it.id }) { message ->
                            ChatMessageItem(message)
                        }
                    }
                }
            }
        } else {
            // Collapsed state - just show a spacer
            Spacer(modifier = Modifier.weight(1f))
        }

        // Chat Footer with input controls
        ChatFooter(
            messageText = messageText,
            onMessageChange = { messageText = it },
            onSend = {
                if (messageText.isNotBlank()) {
                    onSendMessage(messageText)
                    messageText = ""
                }
            },
            onCancel = { messageText = "" },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChatHeader(
    showMessages: Boolean,
    onToggleMessages: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.8f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Live Chat",
            style = MaterialTheme.typography.titleSmall,
            color = Color.White
        )

        IconButton(
            onClick = onToggleMessages,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = if (showMessages) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = if (showMessages) "Hide chat" else "Show chat",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChatFooter(
    messageText: String,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.8f))
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .height(28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Text input field
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(
                    Color.White.copy(alpha = 0.1f),
                    RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (messageText.isEmpty()) {
                Text(
                    text = "Type a message...",
                    style = TextStyle(fontSize = 8.sp),
                    color = Color.White.copy(alpha = 0.4f)
                )
            }
            BasicTextField(
                value = messageText,
                onValueChange = onMessageChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .onKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyUp && keyEvent.key == Key.Enter) {
                            onSend()
                            true
                        } else false
                    },
                textStyle = TextStyle(
                    fontSize = 8.sp,
                    color = Color.White
                ),
                singleLine = true,
                cursorBrush = SolidColor(Color.White)
            )
        }

        // Send button
        IconButton(
            onClick = onSend,
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send message",
                tint = if (messageText.isNotBlank())
                    MaterialTheme.colorScheme.primary
                else
                    Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChatMessageItem(message: ChatMessage) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Color.White.copy(alpha = 0.05f),
                RoundedCornerShape(3.dp)
            )
            .padding(3.dp)
    ) {
        // Row 1: Avatar + Name/Time
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            if (!message.authorPicture.isNullOrEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(message.authorPicture)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Author avatar",
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                // Placeholder avatar
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = (message.authorName?.firstOrNull() ?: message.pubkey.firstOrNull() ?: '?').uppercase(),
                        style = TextStyle(fontSize = 6.sp),
                        color = Color.White
                    )
                }
            }

            // Name and timestamp inline
            Text(
                text = message.authorName ?: message.pubkey.take(8) + "...",
                style = TextStyle(fontSize = 6.sp),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = timeFormat.format(Date(message.createdAt * 1000)),
                style = TextStyle(fontSize = 6.sp),
                color = Color.White.copy(alpha = 0.5f)
            )
        }

        // Row 2: Content (full width)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = message.content,
            style = TextStyle(fontSize = 6.sp),
            color = Color.White
        )
    }
}
