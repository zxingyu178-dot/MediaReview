package com.mediareview.app.feature.v2.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mediareview.app.feature.v2.ui.V2Colors
import com.mediareview.app.feature.v2.ui.V2Spacing
import com.mediareview.app.ui.theme.MediaBackground
import com.mediareview.app.ui.theme.MediaSurface
import com.mediareview.app.ui.theme.MediaTextPrimary
import com.mediareview.app.ui.theme.MediaTextSecondary

/** V2 主导航 Tab。 */
enum class V2MainTab { HOME, REVIEW, FAVORITES, ORGANIZE }

/** 底部主导航：首页 / 批阅 / 收藏 / 整理。 */
@Composable
fun V2BottomNavBar(
    current: V2MainTab,
    onSelect: (V2MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MediaSurface)
            .padding(horizontal = V2Spacing.Sm, vertical = V2Spacing.Sm)
            .navigationBarsPadding(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItem(Icons.Default.Home, "首页", current == V2MainTab.HOME, Modifier.weight(1f)) { onSelect(V2MainTab.HOME) }
        NavItem(Icons.Default.RateReview, "批阅", current == V2MainTab.REVIEW, Modifier.weight(1f)) { onSelect(V2MainTab.REVIEW) }
        NavItem(Icons.Default.Favorite, "收藏", current == V2MainTab.FAVORITES, Modifier.weight(1f)) { onSelect(V2MainTab.FAVORITES) }
        NavItem(Icons.Default.DeleteSweep, "整理", current == V2MainTab.ORGANIZE, Modifier.weight(1f)) { onSelect(V2MainTab.ORGANIZE) }
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) V2Colors.Accent else MediaTextSecondary,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) V2Colors.Accent else MediaTextSecondary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
