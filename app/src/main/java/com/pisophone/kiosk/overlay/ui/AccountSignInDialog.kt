package com.pisophone.kiosk.overlay.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.network.Esp32AccountRequests
import com.pisophone.kiosk.service.AccountController
import kotlinx.coroutines.delay

/**
 * Sign in to, or create, a player account from the lock screen. The account's saved time starts running as soon as the
 * box confirms the PIN. The PIN is sent encrypted and is never stored on the phone.
 */
@Composable
fun AccountSignInDialog(
    surfaceColor: Color,
    primaryColor: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val nameFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        delay(150)
        nameFocus.requestFocus()
        keyboard?.show()
    }

    val fieldColors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
        focusedTextColor = textPrimaryColor,
        unfocusedTextColor = textPrimaryColor,
        focusedBorderColor = primaryColor,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    if (creating) "Create account" else "Sign in",
                    fontWeight = FontWeight.Bold,
                    color = textPrimaryColor,
                    fontSize = 20.sp,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Your unused time is saved in your account, so you do not lose it when you leave.",
                    color = textSecondaryColor,
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { input ->
                        username = input.lowercase().filter { it.isLetterOrDigit() || it == '_' }.take(16)
                        error = ""
                    },
                    label = { Text("Name (3-16 letters, numbers or _)") },
                    singleLine = true,
                    enabled = !busy,
                    textStyle = TextStyle(color = textPrimaryColor, fontSize = 16.sp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { input ->
                        pin = input.filter { it.isDigit() }.take(6)
                        error = ""
                    },
                    label = { Text("PIN (4-6 digits)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    enabled = !busy,
                    textStyle = TextStyle(color = textPrimaryColor, fontSize = 16.sp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error.isNotEmpty()) {
                    Text(error, color = Color(0xFFFF6B6B), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = {
                    creating = !creating
                    error = ""
                }) {
                    Text(
                        if (creating) "Already have an account? Sign in" else "New here? Create an account",
                        color = primaryColor,
                        fontSize = 12.sp,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !busy) {
                        Text("Cancel", color = textSecondaryColor)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        onClick = {
                            if (!Esp32AccountRequests.isValidUsername(username)) {
                                error = Esp32AccountRequests.describe("BAD_NAME")
                            } else if (!Esp32AccountRequests.isValidPin(pin)) {
                                error = Esp32AccountRequests.describe("BAD_PIN_FORMAT")
                            } else {
                                busy = true
                                error = ""
                                val op = if (creating) Esp32AccountRequests.OP_CREATE else Esp32AccountRequests.OP_SIGNIN
                                AccountController.submit(op, username, pin) { reply ->
                                    busy = false
                                    if (reply.success) {
                                        pin = ""
                                        onDismiss()
                                    } else {
                                        error = Esp32AccountRequests.describe(reply.error)
                                    }
                                }
                            }
                        },
                    ) {
                        Text(
                            when {
                                busy -> "Please wait..."
                                creating -> "Create"
                                else -> "Sign in"
                            },
                        )
                    }
                }
            }
        }
    }
}
