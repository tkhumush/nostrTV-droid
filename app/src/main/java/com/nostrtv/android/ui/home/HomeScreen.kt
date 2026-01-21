package com.nostrtv.android.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import com.nostrtv.android.data.nostr.ConnectionState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nostrtv.android.data.auth.SessionStore
import com.nostrtv.android.data.nostr.LiveStream
import com.nostrtv.android.data.nostr.Profile
import com.nostrtv.android.viewmodel.HomeTab
import com.nostrtv.android.viewmodel.HomeViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    onStreamClick: (String) -> Unit,
    onProfileClick: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val context = LocalContext.current
    // Remember SessionStore to avoid recreating on every recomposition
    // (SessionStore creates EncryptedSharedPreferences with crypto operations)
    val sessionStore = remember { SessionStore(context) }
    val savedSession = remember(sessionStore) { sessionStore.getSavedSession() }
    val isLoggedIn = savedSession != null

    val selectedTab by viewModel.selectedTab.collectAsState()
    val curatedStreams by viewModel.curatedStreams.collectAsState()
    val followingStreams by viewModel.followingStreams.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val profiles by viewModel.profiles.collectAsState()

    // Get logged-in user's profile if available
    val userProfile = savedSession?.userPubkey?.let { profiles[it] }

    // Get admin profile
    val adminProfile = profiles[com.nostrtv.android.data.nostr.NostrClient.ADMIN_PUBKEY]

    // Fetch admin profile
    LaunchedEffect(Unit) {
        viewModel.fetchUserProfile(com.nostrtv.android.data.nostr.NostrClient.ADMIN_PUBKEY)
    }

    // Fetch user profile and follow list if logged in
    LaunchedEffect(savedSession?.userPubkey) {
        savedSession?.userPubkey?.let { pubkey ->
            viewModel.fetchUserProfile(pubkey)
            viewModel.setUserPubkey(pubkey)
        } ?: viewModel.setUserPubkey(null)
    }

    // Select streams based on current tab
    val streams = when (selectedTab) {
        HomeTab.CURATED -> curatedStreams
        HomeTab.FOLLOWING -> followingStreams
    }

    // Track the currently focused stream for background (default to first)
    var focusedStream by remember { mutableStateOf<LiveStream?>(null) }
    val backgroundStream = focusedStream ?: streams.firstOrNull()

    Box(modifier = Modifier.fillMaxSize()) {
        // Fullscreen background image from selected stream with crossfade transition
        androidx.compose.animation.Crossfade(
            targetState = backgroundStream?.thumbnailUrl,
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 500),
            label = "background_crossfade"
        ) { thumbnailUrl ->
            if (!thumbnailUrl.isNullOrEmpty()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(thumbnailUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    // Gradient overlay - dark at top for header, fades to transparent
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.8f),
                                        Color.Black.copy(alpha = 0.3f),
                                        Color.Transparent
                                    ),
                                    startY = 0f,
                                    endY = 400f
                                )
                            )
                    )
                }
            }
        }

        Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 48.dp)
    ) {
        // Header: Admin Avatar | nostrTV | Tabs | User Profile
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left side: Admin avatar + nostrTV + Tabs
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Admin Avatar
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (adminProfile?.picture != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(adminProfile.picture)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Admin avatar",
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(
                            text = "N",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }

                // Tabs
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TabButton(
                        text = "Curated",
                        isSelected = selectedTab == HomeTab.CURATED,
                        onClick = { viewModel.selectTab(HomeTab.CURATED) }
                    )
                    TabButton(
                        text = "Following",
                        isSelected = selectedTab == HomeTab.FOLLOWING,
                        onClick = { viewModel.selectTab(HomeTab.FOLLOWING) }
                    )
                }
            }

            // Right side: User profile or Sign In
            if (savedSession != null) {
                // Show user avatar and name when logged in
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onProfileClick() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Avatar
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (userProfile?.picture != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(userProfile.picture)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Profile picture",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(
                                text = (userProfile?.name?.firstOrNull()
                                    ?: savedSession.userPubkey.firstOrNull()
                                    ?: '?').uppercase().toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Name
                    Text(
                        text = userProfile?.displayNameOrName
                            ?: savedSession.userPubkey.take(8) + "...",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                // Show Sign In button when not logged in
                var signInFocused by remember { mutableStateOf(false) }

                androidx.tv.material3.Surface(
                    onClick = onProfileClick,
                    modifier = Modifier.onFocusChanged { signInFocused = it.isFocused },
                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
                        shape = RoundedCornerShape(24.dp)
                    ),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        focusedContainerColor = Color.White,
                        pressedContainerColor = Color.White.copy(alpha = 0.8f)
                    ),
                    scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(
                        scale = 1f,
                        focusedScale = 1f
                    )
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Sign In",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (signInFocused) Color(0xFF1a1a1a) else Color.White
                        )
                    }
                }
            }
        }

        // Content based on tab
        when {
            isLoading && curatedStreams.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = when (connectionState) {
                                is ConnectionState.Disconnected -> "Disconnected"
                                is ConnectionState.Connecting -> "Connecting to relays..."
                                is ConnectionState.Connected -> "Loading streams..."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (connectionState is ConnectionState.Connected) {
                            Text(
                                text = "Subscribed to live streams (kind 30311)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
            selectedTab == HomeTab.FOLLOWING && !isLoggedIn -> {
                // Show sign-in prompt for Following tab when not logged in
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Welcome to nostrTV",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Sign in to see streams from people you follow",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = onProfileClick) {
                            Text("Sign In with Nostr")
                        }
                    }
                }
            }
            streams.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (selectedTab == HomeTab.FOLLOWING) {
                            "No live streams from people you follow"
                        } else {
                            "No live streams found"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            else -> {
                // Push content to bottom
                Spacer(modifier = Modifier.weight(1f))

                // Stream info above cards - updates with focus
                val displayStream = focusedStream ?: streams.firstOrNull()
                val displayStreamerProfile = displayStream?.streamerPubkey?.let { profiles[it] }

                Column(
                    modifier = Modifier.padding(start = 16.dp, bottom = 16.dp)
                ) {
                    // Stream title
                    Text(
                        text = displayStream?.title ?: "",
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Streamer name
                    Text(
                        text = displayStreamerProfile?.displayNameOrName
                            ?: displayStream?.streamerName
                            ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Horizontal scrolling row of stream cards
                TvLazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    items(streams, key = { it.id }) { stream ->
                        val streamerProfile = stream.streamerPubkey?.let { profiles[it] }
                        StreamCard(
                            stream = stream,
                            streamerProfile = streamerProfile,
                            onClick = { onStreamClick(stream.id) },
                            onFocused = { focusedStream = stream }
                        )
                    }
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TabButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { isFocused = it.isFocused },
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(24.dp)
        ),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White,
            pressedContainerColor = Color.White.copy(alpha = 0.8f)
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(
            scale = 1f,
            focusedScale = 1f
        )
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isFocused) Color(0xFF1a1a1a) else Color.White
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StreamCard(
    stream: LiveStream,
    streamerProfile: Profile? = null,
    onClick: () -> Unit,
    onFocused: () -> Unit = {}
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(256.dp)
            .height(144.dp)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .border(
                width = 2.dp,
                color = Color.White.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp)
            ),
        scale = androidx.tv.material3.CardDefaults.scale(
            scale = 1f,
            focusedScale = 1f
        ),
        glow = androidx.tv.material3.CardDefaults.glow(
            glow = androidx.tv.material3.Glow.None,
            focusedGlow = androidx.tv.material3.Glow.None
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Thumbnail image
            if (stream.thumbnailUrl.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(stream.thumbnailUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Stream thumbnail",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                // Placeholder gradient background when no thumbnail
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    MaterialTheme.colorScheme.surface
                                )
                            )
                        )
                )
            }

            // Gradient overlay for text readability
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.3f),
                                Color.Black.copy(alpha = 0.8f)
                            ),
                            startY = 0f,
                            endY = Float.POSITIVE_INFINITY
                        )
                    )
            )

            // LIVE badge (top-left)
            if (stream.status == "live") {
                Box(
                    modifier = Modifier
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Red)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "LIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }

            // Viewer count (top-right)
            if (stream.viewerCount > 0) {
                Box(
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${stream.viewerCount} viewers",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }

            // Title and streamer info (bottom)
            Column(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    text = stream.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Streamer profile picture
                    val profilePicture = streamerProfile?.picture
                    if (!profilePicture.isNullOrEmpty()) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(profilePicture)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Streamer avatar",
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    // Streamer name from profile or fallback
                    val streamerName = streamerProfile?.displayNameOrName
                        ?: stream.streamerName
                        ?: "Unknown streamer"
                    Text(
                        text = streamerName,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
