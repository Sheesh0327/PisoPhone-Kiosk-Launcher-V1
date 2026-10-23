package com.pisophone.kiosk.overlay.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.R
import com.pisophone.kiosk.ui.theme.TechNet
import com.pisophone.kiosk.util.ShopBranding

/** Owner vault: what the box told this phone about the shop's look, read-only (feature 013). It also shows when the box last said it. */
@Composable
fun VaultBrandingCard(context: Context, onShowHelp: (String, String) -> Unit) {
    val branding by ShopBranding.observe(context).collectAsState()
    val rows = BrandingSummary.rows(context, branding, System.currentTimeMillis())
    VaultCard {
        VaultCardTitle(
            title = stringResource(R.string.vault_branding_title),
            onShowHelp = onShowHelp,
            helpText = stringResource(R.string.vault_branding_help),
        )
        rows.forEach { (label, value) ->
            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                VaultCaption(label, modifier = Modifier.padding(end = 12.dp))
                Text(
                    value,
                    color = TechNet.text,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
