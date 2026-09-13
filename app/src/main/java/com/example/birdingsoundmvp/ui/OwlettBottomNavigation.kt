package com.example.birdingsoundmvp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.birdingsoundmvp.R

@Composable
internal fun OwlettBottomNavigation(selected: AppTab, onSelected: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    NavigationBar(modifier = modifier.navigationBarsPadding().height(if (LocalDensity.current.fontScale > 1.2f) 96.dp else 76.dp),
        windowInsets = WindowInsets(0), containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        listOf(AppTab.RECORDING to "录音", AppTab.TRIPS to "计划与行程", AppTab.OWLETT to "Owlett", AppTab.SETTINGS to "设置").forEach { (tab, label) ->
            NavigationBarItem(selected = selected == tab, onClick = { onSelected(tab) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = Color.Transparent),
                label = { Text(if (tab == AppTab.TRIPS && LocalDensity.current.fontScale > 1.2f) "计划与\n行程" else label,
                    style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center) },
                icon = {
                    when (tab) {
                        AppTab.OWLETT -> Icon(painterResource(R.drawable.ic_owlett_outline), null, Modifier.size(26.dp))
                        else -> Icon(when (tab) {
                            AppTab.RECORDING -> Icons.Outlined.MicNone
                            AppTab.TRIPS -> Icons.Outlined.CalendarMonth
                            else -> Icons.Outlined.Settings
                        }, null, Modifier.size(25.dp))
                    }
                })
        }
    }
}
