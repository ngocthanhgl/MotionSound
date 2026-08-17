package com.motionsound.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.motionsound.ui.theme.ComicIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.motionsound.ui.theme.LocalComicColors
import com.motionsound.ui.theme.comicBorder
import com.motionsound.ui.theme.comicPanel
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

private fun hasBgLocation(ctx: Context): Boolean {
    return checkGranted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) &&
        checkGranted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
}

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val ctx = LocalContext.current
    var audioGranted by remember { mutableStateOf(checkGranted(ctx, Manifest.permission.READ_MEDIA_AUDIO)) }
    var notifGranted by remember { mutableStateOf(checkGranted(ctx, Manifest.permission.POST_NOTIFICATIONS)) }
    var locationGranted by remember { mutableStateOf(hasBgLocation(ctx)) }
    var fineLocationGranted by remember { mutableStateOf(checkGranted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) }

    val permissions = remember {
        listOf(
            PermissionInfo(
                Manifest.permission.READ_MEDIA_AUDIO, "Music Access",
                "Read your audio files to build the music library", ComicIcons.MusicNote
            ),
            PermissionInfo(
                Manifest.permission.POST_NOTIFICATIONS, "Notifications",
                "Show media playback controls and driving EQ status", ComicIcons.Notifications
            ),
            PermissionInfo(
                Manifest.permission.ACCESS_FINE_LOCATION, "Location",
                "GPS speed for the car equalizer — grant 'While using' first, then 'Allow all the time' (needed for screen-off driving)", ComicIcons.LocationOn
            )
        )
    }

    val audioLauncher = rememberLauncherForActivityResult(RequestMultiplePermissions()) {
        audioGranted = checkGranted(ctx, Manifest.permission.READ_MEDIA_AUDIO)
    }
    val notifLauncher = rememberLauncherForActivityResult(RequestMultiplePermissions()) {
        notifGranted = checkGranted(ctx, Manifest.permission.POST_NOTIFICATIONS)
    }
    val locationLauncher = rememberLauncherForActivityResult(RequestMultiplePermissions()) {
        fineLocationGranted = checkGranted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        locationGranted = hasBgLocation(ctx)
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

    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
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
                            onRequest = { launchers[idx].launch(
                                if (idx == 2) {
                                    if (!fineLocationGranted) {
                                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                                    } else {
                                        arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                    }
                                } else arrayOf(permissions[idx].permission)
                            ) },
                            onDone = {
                                scope.launch { pagerState.animateScrollToPage(page + 1) }
                            },
                            onOpenSettings = {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:${ctx.packageName}")
                                        )
                                    )
                                }
                            }
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
                val current = pagerState.currentPage == i
                Box(
                    modifier = Modifier
                        .size(if (current) 12.dp else 8.dp)
                        .clip(RoundedCornerShape(0.dp))
                        .background(
                            if (current) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        )
                        .comicBorder(
                            LocalComicColors.current.ink,
                            1.5.dp,
                            cornerRadius = 0.dp
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

    val comic = LocalComicColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .comicPanel(
                containerColor = comic.yellow,
                borderColor = comic.ink,
                shadowColor = comic.shadow,
                borderWidth = 3.dp,
                shadowOffset = 5.dp,
                cornerRadius = 0.dp
            )
            .clickable(onClick = onContinue),
        contentAlignment = Alignment.Center
    ) {
        Text("Continue", style = MaterialTheme.typography.titleMedium, color = comic.ink)
    }
}

@Composable
private fun PermissionPage(
    permission: PermissionInfo,
    granted: Boolean,
    onRequest: () -> Unit,
    onDone: () -> Unit,
    onOpenSettings: () -> Unit
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
            modifier = Modifier.size(12.dp).clip(RoundedCornerShape(0.dp)).background(
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

    val comic = LocalComicColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .comicPanel(
                containerColor = if (granted) comic.green else comic.red,
                borderColor = comic.ink,
                shadowColor = comic.shadow,
                borderWidth = 3.dp,
                shadowOffset = 5.dp,
                cornerRadius = 0.dp
            )
            .clickable { if (granted) onDone() else onRequest() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (granted) "Done" else "Grant Permission",
            style = MaterialTheme.typography.titleSmall,
            color = comic.ink
        )
    }

    if (!granted) {
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onOpenSettings) {
            Text(
                "Open Settings",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun DonePage(onClick: () -> Unit) {
    Icon(
        ComicIcons.CheckCircle, contentDescription = null, modifier = Modifier.size(80.dp),
        tint = LocalComicColors.current.green
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

    val comic = LocalComicColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .comicPanel(
                containerColor = comic.yellow,
                borderColor = comic.ink,
                shadowColor = comic.shadow,
                borderWidth = 3.dp,
                shadowOffset = 5.dp,
                cornerRadius = 0.dp
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("Get Started", style = MaterialTheme.typography.titleMedium, color = comic.ink)
    }
}
