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
import androidx.compose.material.icons.filled.AutoAwesome
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
enum class V2MainTab { HOME, REVIEW, NSFW_REVIEW, FAVORITES, ORGANIZE }

/** 五个一级入口的顺序、文案与图标（JVM 导航测试直接复用）。 */
val V2_MAIN_TAB_ITEMS: List<Triple<V2MainTab, String, ImageVector>> = listOf(
    Triple(V2MainTab.HOME, "首页", Icons.Default.Home),
    Triple(V2MainTab.REVIEW, "批阅", Icons.Default.RateReview),
    Triple(V2MainTab.NSFW_REVIEW, "生图审核", Icons.Default.AutoAwesome),
    Triple(V2MainTab.FAVORITES, "收藏", Icons.Default.Favorite),
    Triple(V2MainTab.ORGANIZE, "整理", Icons.Default.DeleteSweep),
)

/** 底部主导航：首页 / 批阅 / 生图审核 / 收藏 / 整理。 */
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
        V2_MAIN_TAB_ITEMS.forEach { (tab, label, icon) ->
            NavItem(icon, label, current == tab, Modifier.weight(1f)) { onSelect(tab) }
        }
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
