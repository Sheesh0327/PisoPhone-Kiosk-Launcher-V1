package com.pisophone.kiosk.overlay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.pisophone.kiosk.R
import com.pisophone.kiosk.ui.theme.TechNet

/** The note that slides in from the top when a 1v1 arena duel starts: who you are and how many minutes are at stake. */
@Composable
fun ArenaModeBanner(
    visible: Boolean,
    playerRole: Int,
    stakeMinutes: Int,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier.testTag("arena_mode_banner"),
    ) {
        val roleName = when (playerRole) {
            1 -> stringResource(R.string.arena_player_1)
            2 -> stringResource(R.string.arena_player_2)
            else -> stringResource(R.string.arena_participant)
        }
        KidsBanner(
            accent = TechNet.brandFg,
            icon = Icons.Filled.SportsEsports,
            title = stringResource(R.string.banner_arena_title),
            detail = stringResource(R.string.banner_arena_detail, roleName, stakeMinutes),
        )
    }
}
