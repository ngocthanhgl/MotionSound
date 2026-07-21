package com.motionsound.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

data class PermissionInfo(
    val permission: String,
    val title: String,
    val description: String,
    val icon: ImageVector
)

private fun checkGranted(ctx: Context, perm: String): Boolean {
    return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val ctx = LocalContext.current
    var audioGranted by remember { mutableStateOf(checkGranted(ctx, Manifest.permission.READ_MEDIA_AUDIO)) }
    var notifGranted by remember { mutableStateOf(checkGranted(ctx, Manifest.permission.POST_NOTIFICATIONS)) }
    var locationGranted by remember { mutableStateOf(checkGranted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) }

    val permissions = remember {
        listOf(
            PermissionInfo(
                Manifest.permission.READ_MEDIA_AUDIO, "Music Access",
                "Read your audio files to build the music library", Icons.Filled.MusicNote
            ),
            PermissionInfo(
                Manifest.permission.POST_NOTIFICATIONS, "Notifications",
                "Show media playback controls and driving EQ status", Icons.Filled.Notifications
            ),
            PermissionInfo(
                Manifest.permission.ACCESS_FINE_LOCATION, "Location",
                "GPS speed for adaptive car equalizer", Icons.Filled.LocationOn
            )
        )
    }

    val audioLauncher = rememberLauncherForActivityResult(RequestPermission()) {
        audioGranted = checkGranted(ctx, Manifest.permission.READ_MEDIA_AUDIO)
    }
    val notifLauncher = rememberLauncherForActivityResult(RequestPermission()) {
        notifGranted = checkGranted(ctx, Manifest.permission.POST_NOTIFICATIONS)
    }
    val locationLauncher = rememberLauncherForActivityResult(RequestPermission()) {
        locationGranted = checkGranted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val launchers = remember { listOf(audioLauncher, notifLauncher, locationLauncher) }
    val pagerState = rememberPagerState(pageCount = { 5 })
    val scope = rememberCoroutineScope()
    var skippedPage1 by remember { mutableStateOf(false) }
    var skippedPage2 by remember { mutableStateOf(false) }
    var skippedPage3 by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val target = when {
            !audioGranted -> 0
            !notifGranted -> 1
            !locationGranted -> 2
            else -> 4
        }
        if (target > 0) pagerState.animateScrollToPage(target)
    }

    LaunchedEffect(pagerState.currentPage) {
        when (pagerState.currentPage) {
            1 -> if (audioGranted && !skippedPage1) { skippedPage1 = true; pagerState.animateScrollToPage(2) }
            2 -> if (notifGranted && !skippedPage2) { skippedPage2 = true; pagerState.animateScrollToPage(3) }
            3 -> if (locationGranted && !skippedPage3) { skippedPage3 = true; pagerState.animateScrollToPage(4) }
        }
    }

    LaunchedEffect(audioGranted) {
        if (audioGranted && pagerState.currentPage == 1) pagerState.animateScrollToPage(2)
    }
    LaunchedEffect(notifGranted) {
        if (notifGranted && pagerState.currentPage == 2) pagerState.animateScrollToPage(3)
    }
    LaunchedEffect(locationGranted) {
        if (locationGranted && pagerState.currentPage == 3) pagerState.animateScrollToPage(4)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.statusBarsPadding())

        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                when (page) {
                    0 -> WelcomeContent(onContinue = {
                        scope.launch { pagerState.animateScrollToPage(1) }
                    })
                    1, 2, 3 -> {
                        val idx = page - 1
                        PermissionPage(
                            permission = permissions[idx],
                            granted = when (idx) {
                                0 -> audioGranted; 1 -> notifGranted
                                else -> locationGranted
                            },
                            onRequest = { launchers[idx].launch(permissions[idx].permission) }
                        )
                    }
                    4 -> DonePage(onClick = onComplete)
                }
            }
        }

        Row(
            modifier = Modifier.padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 0 until 5) {
                Box(
                    modifier = Modifier
                        .size(if (pagerState.currentPage == i) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (pagerState.currentPage == i) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        )
                )
            }
        }
    }
}

@Composable
private fun WelcomeContent(onContinue: () -> Unit) {
    Icon(
        painter = painterResource(com.motionsound.R.drawable.ic_launcher_foreground),
        contentDescription = "MotionSound",
        modifier = Modifier.size(80.dp),
        tint = MaterialTheme.colorScheme.primary
    )

    Spacer(Modifier.height(24.dp))

    Text(
        text = "MotionSound",
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )

    Spacer(Modifier.height(12.dp))

    Text(
        text = "Adaptive audio for your drive.",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(32.dp))

    Text(
        text = "Please stay focused on the road.",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface
    )

    Spacer(Modifier.height(32.dp))

    Button(
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text("Continue", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PermissionPage(
    permission: PermissionInfo,
    granted: Boolean,
    onRequest: () -> Unit
) {
    Spacer(Modifier.height(8.dp))

    Icon(
        permission.icon, contentDescription = null, modifier = Modifier.size(64.dp),
        tint = if (granted) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(24.dp))

    Text(
        permission.title, style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
    )

    Spacer(Modifier.height(12.dp))

    Text(
        permission.description, style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(32.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(12.dp).clip(CircleShape).background(
                if (granted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (granted) "Granted" else "Not Granted",
            style = MaterialTheme.typography.labelLarge,
            color = if (granted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error
        )
    }

    Spacer(Modifier.height(32.dp))

    Button(
        onClick = { if (!granted) onRequest() },
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(12.dp),
        enabled = !granted
    ) {
        Text(if (granted) "Done" else "Grant Permission", fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DonePage(onClick: () -> Unit) {
    Icon(
        Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(80.dp),
        tint = MaterialTheme.colorScheme.primary
    )

    Spacer(Modifier.height(24.dp))

    Text(
        text = "All Set!",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )

    Spacer(Modifier.height(12.dp))

    Text(
        text = "You're ready to hit the road.",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(32.dp))

    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text("Get Started", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}
