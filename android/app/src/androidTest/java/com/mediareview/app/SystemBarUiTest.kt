package com.mediareview.app

import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 8D.1 §27~§30：深色 App 的系统栏图标合同（设备侧）。
 *
 * 深色背景下状态栏/导航栏必须使用**浅色图标**（isAppearanceLightXxxBars == false），
 * 否则时间/信号/电量会出现黑图标看不清。
 */
@RunWith(AndroidJUnit4::class)
class SystemBarUiTest {

    @Test
    fun darkAppUsesLightSystemBarIcons() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                assertFalse(
                    "深色 App 状态栏必须使用浅色图标",
                    controller.isAppearanceLightStatusBars,
                )
                assertFalse(
                    "深色 App 导航栏必须使用浅色图标",
                    controller.isAppearanceLightNavigationBars,
                )
            }
        } finally {
            scenario.close()
        }
    }
}
