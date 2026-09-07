package com.pisophone.kiosk.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Robust, interactive first-time setup tutorial.
 * Operates purely on declarative Compose state without blocking main-thread TTS bindings
 * or forced scroll-lock loops that cause UI freezing and lifecycle crashes.
 */
@Composable
fun TutorialScreen(
    onCompleteTutorial: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentStep by remember { mutableIntStateOf(0) }
    val totalSteps = 3

    val steps = listOf(
        TutorialStepData(
            title = "Floating Game Space HUD",
            subtitle = "Quick-access timer & controls anywhere",
            description = "A floating timer pill remains accessible on the screen. Tap it anytime to open the Game Space HUD where users can add time via coin slot, adjust brightness and volume, or monitor remaining session time.",
            icon = Icons.Filled.Timer,
            iconTint = Color(0xFF00E5FF)
        ),
        TutorialStepData(
            title = "Pinned Games & App Drawer",
            subtitle = "Streamlined kiosk interface",
            description = "Your favorite games and permitted apps appear pinned at the top. The organized app drawer below provides easy access to all authorized entertainment apps while keeping system settings securely locked.",
            icon = Icons.Filled.SportsEsports,
            iconTint = Color(0xFF10B981)
        ),
        TutorialStepData(
            title = "Ready to Launch",
            subtitle = "Kiosk mode & hardware security",
            description = "The kiosk automatically links to your ESP32 Master coin box over local Wi-Fi. Press Finish to activate and start serving users.",
            icon = Icons.Filled.Security,
            iconTint = Color(0xFF8B5CF6)
        )
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header Bar with Skip Action
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "PISOPHONE KIOSK",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
            }

            TextButton(
                onClick = onCompleteTutorial,
                modifier = Modifier.testTag("skip_tutorial_button")
            ) {
                Text(
                    text = "Skip",
                    color = Color(0xFF94A3B8),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Progress Indicators
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (i in 0 until totalSteps) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (i <= currentStep) Color(0xFF10B981) else Color(0xFF334155)
                        )
                )
            }
        }

        // Main Scrollable Interactive Body
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "TutorialStepAnimation"
            ) { step ->
                val stepData = steps[step]
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Feature Icon Badge
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(stepData.iconTint.copy(alpha = 0.15f), CircleShape)
                            .border(1.dp, stepData.iconTint.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = stepData.icon,
                            contentDescription = null,
                            tint = stepData.iconTint,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stepData.title,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = stepData.subtitle,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = stepData.iconTint,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = stepData.description,
                        fontSize = 14.sp,
                        color = Color(0xFF94A3B8),
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Mock Visual Frame (Step specific highlight)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .height(280.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF060B14))
                            .border(2.dp, Color(0xFF1E293B), RoundedCornerShape(20.dp))
                            .padding(12.dp)
                    ) {
                        TutorialMockUI(currentStep = step)
                    }
                }
            }
        }

        // Bottom Navigation Buttons
        Surface(
            color = Color(0xFF060B14),
            border = BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentStep > 0) {
                    OutlinedButton(
                        onClick = { currentStep -= 1 },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("tutorial_back_button"),
                        border = BorderStroke(1.dp, Color(0xFF334155)),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8))
                    ) {
                        Text("Back", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Button(
                    onClick = {
                        if (currentStep < totalSteps - 1) {
                            currentStep += 1
                        } else {
                            onCompleteTutorial()
                        }
                    },
                    modifier = Modifier
                        .weight(if (currentStep > 0) 1.5f else 1f)
                        .height(52.dp)
                        .testTag("tutorial_next_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    val isLast = currentStep == totalSteps - 1
                    Icon(
                        imageVector = if (isLast) Icons.Filled.Check else Icons.Filled.ArrowForward,
                        contentDescription = null,
                        tint = Color(0xFF022C22),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isLast) "Finish & Launch" else "Next",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF022C22)
                    )
                }
            }
        }
    }
}

private data class TutorialStepData(
    val title: String,
    val subtitle: String,
    val description: String,
    val icon: ImageVector,
    val iconTint: Color
)

@Composable
private fun TutorialMockUI(currentStep: Int) {
    val bg = Color(0xFF060B14)
    val cardBg = Color(0xFF0D1527)
    val border = Color(0xFF1E293B)
    val textPrimary = Color(0xFFF8FAFC)
    val textMuted = Color(0xFF64748B)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(bg)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bolt, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("PISOPHONE", fontSize = 12.sp, fontWeight = FontWeight.Black, color = textPrimary)
                }
                Text("12:00 PM", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textPrimary)
            }

            HorizontalDivider(color = border, thickness = 1.dp)

            // Pinned Section
            Column(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .then(
                        if (currentStep == 1) Modifier
                            .background(Color(0xFF10B981).copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF10B981).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(6.dp)
                        else Modifier
                    )
            ) {
                Text("PINNED GAMES", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = textMuted)
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(4) {
                        Box(
                            modifier = Modifier.weight(1f).height(44.dp).background(cardBg, RoundedCornerShape(8.dp)).border(1.dp, border, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.SportsEsports, contentDescription = null, tint = Color(0xFF10B981).copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            // Search Bar
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).height(34.dp).background(Color(0xFF0F172A), RoundedCornerShape(6.dp)).border(1.dp, border, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(modifier = Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = textMuted, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Search apps & games...", fontSize = 11.sp, color = textMuted)
                }
            }

            // Apps Row
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(4) {
                    Box(
                        modifier = Modifier.weight(1f).height(44.dp).background(cardBg, RoundedCornerShape(8.dp)).border(1.dp, border, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Apps, contentDescription = null, tint = textMuted.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // Floating Timer Pill (Highlighted during Step 0)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 10.dp)
                .height(28.dp)
                .wrapContentWidth()
                .background(Color(0xFF0F172A).copy(alpha = 0.95f), CircleShape)
                .border(
                    width = if (currentStep == 0) 2.dp else 1.dp,
                    color = if (currentStep == 0) Color(0xFF00E5FF) else Color(0xFF00E5FF).copy(alpha = 0.5f),
                    shape = CircleShape
                )
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(modifier = Modifier.size(6.dp).background(Color(0xFF00E5FF), CircleShape))
                Text(text = "30:00", color = Color.White, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}
