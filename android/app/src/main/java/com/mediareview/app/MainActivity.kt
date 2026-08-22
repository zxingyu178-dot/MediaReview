package com.mediareview.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.mediareview.app.feature.library.LibraryDestinations
import com.mediareview.app.feature.home.HomeDestinations
import com.mediareview.app.feature.mediawall.MediaWallDestinations
import com.mediareview.app.feature.connect.ConnectDestinations
import com.mediareview.app.feature.deletequeue.deleteQueueGraph
import com.mediareview.app.feature.duplicates.duplicatesGraph
import com.mediareview.app.feature.favorites.favoritesGraph
import com.mediareview.app.feature.library.libraryGraph
import com.mediareview.app.feature.home.homeGraph
import com.mediareview.app.feature.mediawall.mediaWallGraph
import com.mediareview.app.feature.connect.connectGraph
import com.mediareview.app.feature.player.playerGraph
import com.mediareview.app.feature.review.reviewGraph
import com.mediareview.app.feature.settings.SettingsDestinations
import com.mediareview.app.feature.settings.settingsGraph
import com.mediareview.app.feature.viewer.imageViewerGraph
import com.mediareview.app.ui.theme.MediaReviewTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MediaReviewTheme {
                MediaReviewRoot()
            }
        }
    }
}

@Composable
fun MediaReviewRoot() {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = ConnectDestinations.CONNECT,
    ) {
        connectGraph(navController)
        homeGraph(navController)
        libraryGraph(navController)
        mediaWallGraph(navController)
        imageViewerGraph(navController)
        playerGraph(navController)
        reviewGraph(navController)
        favoritesGraph(navController)
        deleteQueueGraph(navController)
        duplicatesGraph(navController)
        settingsGraph(navController)
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    MediaReviewTheme {
        MediaReviewRoot()
    }
}