package com.pisophone.kiosk.ui

import android.speech.tts.TextToSpeech
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun TutorialScreen(
    onCompleteTutorial: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    
    var tts: TextToSpeech? by remember { mutableStateOf(null) }
    var isTtsReady by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val ttsInstance = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsReady = true
            }
        }
        tts = ttsInstance
        onDispose {
            ttsInstance.stop()
            ttsInstance.shutdown()
        }
    }

    LaunchedEffect(isTtsReady) {
        if (isTtsReady) {
            tts?.language = Locale.US
            tts?.speak(
                "Welcome! This is a one-time guided instruction for your Piso Phone launcher kiosk.",
                TextToSpeech.QUEUE_FLUSH, null, null
            )
            delay(5000)
            scrollState.animateScrollTo(400, animationSpec = tween(durationMillis = 2000))
            tts?.speak(
                "Notice the floating timer pill on the screen. Tap it anytime to open the Game Space HUD where users can add time, adjust brightness and volume, boost RAM, or check their session balance.",
                TextToSpeech.QUEUE_ADD, null, null
            )
            delay(6000)
            scrollState.animateScrollTo(800, animationSpec = tween(durationMillis = 2000))
            tts?.speak(
                "The interface includes a pinned apps section for your favorite games, and an organized app drawer below.",
                TextToSpeech.QUEUE_ADD, null, null
            )
            delay(5000)
            scrollState.animateScrollTo(scrollState.maxValue, animationSpec = tween(durationMillis = 2000))
            tts?.speak(
                "When you are ready, click Finish below to exit the tutorial and enter your new kiosk.",
                TextToSpeech.QUEUE_ADD, null, null
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Main content area that scrolls
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            
            Text(
                text = "Welcome to PisoPhone Kiosk",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            Text(
                text = "This is a one-time guided instruction for your PisoPhone launcher kiosk. Below is a preview of what the interface looks like.",
                fontSize = 14.sp,
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            // Phone Frame Mockup
            Box(
                modifier = Modifier
                    .width(300.dp)
                    .height(600.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color.Black)
                    .border(6.dp, Color(0xFF334155), RoundedCornerShape(32.dp))
                    .padding(8.dp)
            ) {
                // Mock Launcher UI
                MockLauncherUI()
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                text = "The launcher is designed to be completely secure and robust. When you click Finish, you will be directed to activate your device, or enter the launcher if already activated.",
                fontSize = 14.sp,
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
            )
        }
        
        // Bottom Action Bar
        Surface(
            color = Color(0xFF060B14),
            border = BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier.padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = {
                        tts?.stop()
                        onCompleteTutorial()
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color(0xFF022C22),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Finish Tutorial",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF022C22)
                    )
                }
            }
        }
    }
}

@Composable
fun MockLauncherUI() {
    val bg = Color(0xFF060B14)
    val cardBg = Color(0xFF0D1527)
    val border = Color(0xFF1E293B)
    val textPrimary = Color(0xFFF8FAFC)
    val textMuted = Color(0xFF64748B)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(24.dp))
                .background(bg)
        ) {
            // Mock Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bolt, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("PISOPHONE", fontSize = 14.sp, fontWeight = FontWeight.Black, color = textPrimary)
                }
                Text("12:00 PM", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = textPrimary)
            }
            
            HorizontalDivider(color = border, thickness = 1.dp)

            // Mock Pinned Apps
            Column(modifier = Modifier.padding(16.dp)) {
                Text("PINNED", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = textMuted, modifier = Modifier.padding(bottom = 8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(4) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .background(cardBg, RoundedCornerShape(12.dp))
                                .border(1.dp, border, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.TouchApp, contentDescription = null, tint = Color(0xFF10B981).copy(alpha = 0.5f))
                        }
                    }
                }
            }

            HorizontalDivider(color = border, thickness = 1.dp)

            // Mock Search Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(48.dp)
                    .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                    .border(1.dp, border, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(modifier = Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = textMuted, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Search games & apps...", fontSize = 12.sp, color = textMuted)
                }
            }

            // Mock App Grid
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                repeat(4) { row ->
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        repeat(4) { col ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .background(cardBg, RoundedCornerShape(12.dp))
                                    .border(1.dp, border, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.Apps, contentDescription = null, tint = textMuted.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        }
        
        // Mock Floating Timer Pill (Matching actual floating ball overlay)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 18.dp, end = 16.dp)
                .height(28.dp)
                .wrapContentWidth()
                .background(Color(0xFF0F172A).copy(alpha = 0.9f), CircleShape)
                .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.85f), CircleShape)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(Color(0xFF00E5FF), CircleShape)
                )
                Text(
                    text = "30:00",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }
    }
}
