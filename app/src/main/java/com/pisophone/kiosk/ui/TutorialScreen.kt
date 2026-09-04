package com.pisophone.kiosk.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.pisophone.kiosk.ui.theme.Background
import com.pisophone.kiosk.ui.theme.CardBackground
import com.pisophone.kiosk.ui.theme.CardBorder
import com.pisophone.kiosk.ui.theme.EmeraldAccent
import com.pisophone.kiosk.ui.theme.TextMuted
import com.pisophone.kiosk.ui.theme.TextPrimary
import com.pisophone.kiosk.ui.theme.TextSecondary

data class TutorialStep(
    val stepNumber: Int,
    val title: String,
    val subtitle: String,
    val description: String,
    val icon: ImageVector,
    val accentColor: Color,
    val features: List<TutorialFeature>,
    val interactiveType: TutorialInteractiveType
)

data class TutorialFeature(
    val icon: ImageVector,
    val title: String,
    val detail: String
)

enum class TutorialInteractiveType {
    OVERVIEW,
    COIN_SLOT_SIMULATION,
    FLOATING_BALL_DEMO,
    ACTIVATION_PREVIEW
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun TutorialScreen(
    onCompleteTutorial: () -> Unit,
    modifier: Modifier = Modifier
) {
    val steps = remember {
        listOf(
            TutorialStep(
                stepNumber = 1,
                title = "WELCOME TO PISOPHONE",
                subtitle = "Commercial Arcade & Coin Kiosk System",
                description = "Your phone transforms into an automated, revenue-generating coin arcade. Let's walk through how the system operates with your coin slot hardware.",
                icon = Icons.Filled.Sensors,
                accentColor = EmeraldAccent,
                features = listOf(
                    TutorialFeature(
                        icon = Icons.Filled.Paid,
                        title = "Hardware Pulse Sync",
                        detail = "Connects via USB OTG or ESP32 WiFi to register coin pulses instantaneously."
                    ),
                    TutorialFeature(
                        icon = Icons.Filled.Lock,
                        title = "Kiosk Lockdown Mode",
                        detail = "Pinning, status bar blocking, and watchdog protection keep players inside games."
                    ),
                    TutorialFeature(
                        icon = Icons.Filled.Security,
                        title = "Device Cryptographic Seal",
                        detail = "Tied directly to your phone's unique hardware identifier for tamper protection."
                    )
                ),
                interactiveType = TutorialInteractiveType.OVERVIEW
            ),
            TutorialStep(
                stepNumber = 2,
                title = "COIN INSERTION & SESSION TIME",
                subtitle = "Test Real-Time Credit Handling",
                description = "When customers insert coins into the external coin acceptor, the kiosk converts each pulse into active play time in real time.",
                icon = Icons.Filled.Paid,
                accentColor = Color(0xFFF59E0B),
                features = listOf(
                    TutorialFeature(
                        icon = Icons.Filled.Paid,
                        title = "Configurable Rates",
                        detail = "Set minutes per coin (e.g., 5 mins = ₱1, 10 mins = ₱5) in the hidden admin vault."
                    ),
                    TutorialFeature(
                        icon = Icons.Filled.TouchApp,
                        title = "Grace Period & Timeout",
                        detail = "A 60-second insertion window allows users to drop multiple coins before session begins."
                    )
                ),
                interactiveType = TutorialInteractiveType.COIN_SLOT_SIMULATION
            ),
            TutorialStep(
                stepNumber = 3,
                title = "FLOATING BALL & SCREEN LOCK",
                subtitle = "Seamless Overlay Experience",
                description = "While playing, a discreet floating badge shows remaining time. When time runs out, the kiosk gracefully locks back until coins are inserted.",
                icon = Icons.Filled.TouchApp,
                accentColor = Color(0xFF38BDF8),
                features = listOf(
                    TutorialFeature(
                        icon = Icons.Filled.TouchApp,
                        title = "Interactive Floating Timer",
                        detail = "Draggable widget lets users check remaining time or tap 'Insert Coin' anytime."
                    ),
                    TutorialFeature(
                        icon = Icons.Filled.Settings,
                        title = "Hidden Owner Vault",
                        detail = "Hold 5 taps on the top logo or timer with your admin PIN to access sales logs."
                    )
                ),
                interactiveType = TutorialInteractiveType.FLOATING_BALL_DEMO
            ),
            TutorialStep(
                stepNumber = 4,
                title = "1-CLICK USB ACTIVATION",
                subtitle = "Consistent Hardware ID & Fast WebADB",
                description = "Connect your phone to your computer via USB cable. Open the PisoPhone portal (pisophone.pages.dev/activate.html) to read your device ID and activate with 1 click!",
                icon = Icons.Filled.Usb,
                accentColor = EmeraldAccent,
                features = listOf(
                    TutorialFeature(
                        icon = Icons.Filled.Usb,
                        title = "1-Click USB Activation",
                        detail = "WebADB reads your consistent hardware ID directly from the device and pushes your license key."
                    ),
                    TutorialFeature(
                        icon = Icons.Filled.CheckCircle,
                        title = "Manual License Key",
                        detail = "You can also paste or type your commercial key directly on the device."
                    )
                ),
                interactiveType = TutorialInteractiveType.ACTIVATION_PREVIEW
            )
        )
    }

    var currentStepIndex by remember { mutableIntStateOf(0) }
    val currentStep = steps[currentStepIndex]
    val isLastStep = currentStepIndex == steps.lastIndex

    Surface(
        modifier = modifier.fillMaxSize(),
        color = Background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            // Header Bar: Step indicator & Skip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back button if past step 1
                if (currentStepIndex > 0) {
                    IconButton(
                        onClick = { currentStepIndex-- },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                            .testTag("tutorial_back_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Previous step",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    Box(modifier = Modifier.size(40.dp))
                }

                // Step Pills
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    steps.indices.forEach { index ->
                        val isCurrent = index == currentStepIndex
                        val isPassed = index < currentStepIndex
                        Box(
                            modifier = Modifier
                                .height(6.dp)
                                .width(if (isCurrent) 28.dp else 12.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    when {
                                        isCurrent -> EmeraldAccent
                                        isPassed -> Color(0xFF047857)
                                        else -> Color(0xFF334155)
                                    }
                                )
                        )
                    }
                }

                // Skip button
                Text(
                    text = "Skip",
                    color = TextMuted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onCompleteTutorial() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("tutorial_skip_button")
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Step Content Animated
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    if (targetState.stepNumber > initialState.stepNumber) {
                        (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                            slideOutHorizontally { width -> -width } + fadeOut()
                        )
                    } else {
                        (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                            slideOutHorizontally { width -> width } + fadeOut()
                        )
                    }
                },
                modifier = Modifier.weight(1f)
            ) { step ->
                TutorialStepBody(step = step)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bottom Navigation Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (isLastStep) {
                            onCompleteTutorial()
                        } else {
                            currentStepIndex++
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("tutorial_next_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldAccent,
                        contentColor = Color(0xFF020617)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = if (isLastStep) "Continue to Device Activation" else "Next Step",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        if (isLastStep) Icons.Filled.CheckCircle else Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun TutorialStepBody(step: TutorialStep) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Step Badge & Icon
        Box(
            modifier = Modifier
                .size(76.dp)
                .background(step.accentColor.copy(alpha = 0.15f), CircleShape)
                .border(1.5.dp, step.accentColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = step.icon,
                contentDescription = null,
                tint = step.accentColor,
                modifier = Modifier.size(38.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = step.title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            color = step.accentColor,
            letterSpacing = 1.2.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = step.subtitle,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = step.description,
            fontSize = 13.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Interactive Widget Demo for Step 2 and Step 3
        when (step.interactiveType) {
            TutorialInteractiveType.COIN_SLOT_SIMULATION -> {
                InteractiveCoinSlotCard()
                Spacer(modifier = Modifier.height(16.dp))
            }
            TutorialInteractiveType.FLOATING_BALL_DEMO -> {
                InteractiveFloatingBallCard()
                Spacer(modifier = Modifier.height(16.dp))
            }
            TutorialInteractiveType.ACTIVATION_PREVIEW -> {
                ActivationPreviewCard()
                Spacer(modifier = Modifier.height(16.dp))
            }
            TutorialInteractiveType.OVERVIEW -> {
                // Feature List Cards
            }
        }

        // Features list
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            step.features.forEach { feature ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    border = BorderStroke(1.dp, CardBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(Color(0xFF1E293B), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                feature.icon,
                                contentDescription = null,
                                tint = step.accentColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = feature.title,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = feature.detail,
                                fontSize = 12.sp,
                                color = TextSecondary,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Interactive Simulation for Step 2: Coin Insertion & Credit accumulation
 */
@Composable
fun InteractiveCoinSlotCard() {
    var simulatedCoins by remember { mutableIntStateOf(0) }
    val minutesPerCoin = 5
    val totalMinutes = simulatedCoins * minutesPerCoin

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1917)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "INTERACTIVE COIN TEST",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF59E0B),
                    letterSpacing = 1.sp
                )
                Text(
                    "Tap to simulate hardware pulse",
                    fontSize = 10.sp,
                    color = Color(0xFFA8A29E)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$simulatedCoins",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                    Text("Coins Inserted", fontSize = 11.sp, color = TextMuted)
                }

                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .width(1.dp)
                        .background(Color(0xFF334155))
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${totalMinutes}m",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFFF59E0B),
                        fontFamily = FontFamily.Monospace
                    )
                    Text("Play Time Added", fontSize = 11.sp, color = TextMuted)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { simulatedCoins++ },
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .testTag("simulate_insert_coin_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF59E0B),
                        contentColor = Color(0xFF1C1917)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Filled.Paid, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Drop ₱1 Coin", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                if (simulatedCoins > 0) {
                    OutlinedButton(
                        onClick = { simulatedCoins = 0 },
                        modifier = Modifier.height(42.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFA8A29E)),
                        border = BorderStroke(1.dp, Color(0xFF334155)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Reset", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/**
 * Interactive Preview for Step 3: Floating Ball Widget Demo
 */
@Composable
fun InteractiveFloatingBallCard() {
    var isBallTapped by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0C2135)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "FLOATING ASSISTANT PREVIEW",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF38BDF8),
                    letterSpacing = 1.sp
                )
                Text(
                    "Draggable in real games",
                    fontSize = 10.sp,
                    color = Color(0xFF94A3B8)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Simulated floating pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF0F172A))
                    .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(24.dp))
                    .clickable { isBallTapped = !isBallTapped }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("preview_floating_ball")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(Color(0xFF10B981), CircleShape)
                    )
                    Text(
                        "14:59",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "Tap to expand",
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            AnimatedVisibility(visible = isBallTapped) {
                Column(
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth()
                        .background(Color(0xFF020617), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Floating Menu Expands: Players can add more coins or finish session cleanly without leaving their game.",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Preview Card for Step 4: Activation preview
 */
@Composable
fun ActivationPreviewCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, EmeraldAccent.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF042F2E)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = EmeraldAccent,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "READY FOR ACTIVATION",
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                color = EmeraldAccent,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Click Finish below to open the Activation screen. Connect your phone via USB or enter your license key to activate.",
                fontSize = 12.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}
