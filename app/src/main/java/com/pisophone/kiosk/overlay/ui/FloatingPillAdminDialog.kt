package com.pisophone.kiosk.overlay.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.R
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.ui.theme.TechNet
import kotlinx.coroutines.delay

/**
 * The owner's sign-in: the admin password, a wrong one is said in words right under the field. [extra] adds more actions
 * under the field (the lock screen has two recovery doors there). The password is checked by [KioskSecurity.verifyAdminPin].
 */
@Composable
fun OwnerSignIn(
    context: Context,
    onDismiss: () -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    extra: (@Composable (enteredPassword: String, wrongPassword: () -> Unit) -> Unit)? = null,
) {
    var enteredPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    val pinFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        delay(150)
        pinFocusRequester.requestFocus()
        keyboardController?.show()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AdminPanelSettings, contentDescription = null, tint = TechNet.brandFg, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text("Owner sign-in", fontWeight = FontWeight.ExtraBold, color = TechNet.text, fontSize = 18.sp, lineHeight = 24.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text("Enter the admin password of this box.", color = TechNet.textMuted, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = enteredPin,
            onValueChange = {
                if (it.length <= 32) {
                    enteredPin = it
                    pinError = false
                }
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            placeholder = { Text("Password", color = TechNet.textMuted) },
            singleLine = true,
            isError = pinError,
            textStyle = TextStyle(color = TechNet.text, fontSize = 16.sp),
            colors = vaultTextFieldColors(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().focusRequester(pinFocusRequester),
        )
        if (pinError) {
            Text("Wrong password.", color = TechNet.dangerText, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PisoButton(text = stringResource(R.string.ui_cancel), onClick = onDismiss, modifier = Modifier.weight(1f), style = PisoButtonStyle.Outline, size = PisoButtonSize.Small)
            PisoButton(
                text = stringResource(R.string.ui_unlock),
                onClick = {
                    if (KioskSecurity.verifyAdminPin(context, enteredPin)) {
                        onUnlock()
                    } else {
                        pinError = true
                    }
                },
                modifier = Modifier.weight(1f),
                size = PisoButtonSize.Small,
            )
        }
        if (extra != null) {
            Spacer(Modifier.height(12.dp))
            extra(enteredPin) { pinError = true }
        }
    }
}

/** The sign-in inside the pill overlay's own card. */
@Composable
fun FloatingPillAdminAuthCard(
    context: Context,
    onDismiss: () -> Unit,
    onUnlockSuccess: () -> Unit,
) {
    OwnerSignIn(context = context, onDismiss = onDismiss, onUnlock = onUnlockSuccess)
}
