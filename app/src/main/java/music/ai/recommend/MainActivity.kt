package music.ai.recommend

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import music.ai.recommend.ui.*
import music.ai.recommend.ui.theme.AiMusicTheme
import music.ai.recommend.ui.theme.BackgroundTone
import music.ai.recommend.ui.theme.LocalSurfaceScrim
import music.ai.recommend.ui.theme.LocalWallpaperScrim

class MainActivity : ComponentActivity() {
    private var openPlayerAction by mutableStateOf(false)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        enableEdgeToEdge()
        setContent {
            // Resolved before the theme: the wallpaper and its opacity decide what colours are
            // legible, so the theme has to be built from them.
            val viewModel: MusicViewModel = viewModel()
            val backgroundImageUri by viewModel.backgroundImageUri.collectAsState()
            val backgroundAlpha by viewModel.backgroundAlpha.collectAsState()
            val backgroundTone by viewModel.backgroundTone.collectAsState()
            val hasWallpaper = backgroundImageUri != null

            AiMusicTheme(
                backgroundTone = if (hasWallpaper) backgroundTone else BackgroundTone.Unknown,
                backgroundAlpha = if (hasWallpaper) backgroundAlpha else 0f
            ) {
                val folders by viewModel.folders.collectAsState()
                val playlists by viewModel.playlists.collectAsState()
                val smartAlbums by viewModel.smartAlbums.collectAsState()

                val folderNavController = rememberNavController()
                val playlistNavController = rememberNavController()
                var showPlayer by remember { mutableStateOf(false) }
                val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
                val scope = rememberCoroutineScope()

                val permissions = remember {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        arrayOf(
                            Manifest.permission.READ_MEDIA_AUDIO,
                            Manifest.permission.POST_NOTIFICATIONS,
                            Manifest.permission.RECORD_AUDIO
                        )
                    } else {
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.RECORD_AUDIO
                        )
                    }
                }

                var permissionsGranted by remember {
                    mutableStateOf(permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
                }

                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { results ->
                    permissionsGranted = results.values.all { it }
                }

                LaunchedEffect(openPlayerAction) {
                    if (openPlayerAction) {
                        showPlayer = true
                        openPlayerAction = false
                    }
                }

                LaunchedEffect(permissionsGranted) {
                    if (permissionsGranted) viewModel.loadMusic()
                }

                if (!permissionsGranted) {
                    StartScreen(onGrantPermissions = { launcher.launch(permissions) })

                    DisposableEffect(Unit) {
                        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                permissionsGranted = permissions.all {
                                    checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
                                }
                            }
                        }
                        lifecycle.addObserver(observer)
                        onDispose { lifecycle.removeObserver(observer) }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // Replaces an empty full-screen Surface that existed only to paint this
                            // colour, which cost a full-screen overdraw on every frame.
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        AppBackground(backgroundImageUri, backgroundAlpha)

                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            containerColor = Color.Transparent,
                            bottomBar = {
                                Column {
                                    PlayerOverlay(
                                        viewModel = viewModel,
                                        onClick = { showPlayer = true }
                                    )
                                    NavigationBar(
                                        containerColor = MaterialTheme.colorScheme.surface.copy(
                                            alpha = LocalSurfaceScrim.current
                                        )
                                    ) {
                                        NavigationBarItem(
                                            icon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null) },
                                            label = { Text(stringResource(id = R.string.nav_playlists)) },
                                            selected = pagerState.currentPage == 0,
                                            onClick = { scope.launch { pagerState.animateScrollToPage(0) } }
                                        )
                                        NavigationBarItem(
                                            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                                            label = { Text(stringResource(id = R.string.nav_music)) },
                                            selected = pagerState.currentPage == 1,
                                            onClick = { scope.launch { pagerState.animateScrollToPage(1) } }
                                        )
                                        NavigationBarItem(
                                            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                            label = { Text(stringResource(id = R.string.nav_settings)) },
                                            selected = pagerState.currentPage == 2,
                                            onClick = { scope.launch { pagerState.animateScrollToPage(2) } }
                                        )
                                    }
                                }
                            }
                        ) { innerPadding ->
                            Box(modifier = Modifier.padding(innerPadding)) {
                                HorizontalPager(
                                    state = pagerState,
                                    modifier = Modifier.fillMaxSize()
                                ) { page ->
                                    when (page) {
                                        0 -> NavHost(
                                            navController = playlistNavController,
                                            startDestination = "playlist_list",
                                            enterTransition = { fadeIn() + slideInHorizontally { it / 2 } },
                                            exitTransition = { fadeOut() + slideOutHorizontally { -it / 2 } },
                                            popEnterTransition = { fadeIn() + slideInHorizontally { -it / 2 } },
                                            popExitTransition = { fadeOut() + slideOutHorizontally { it / 2 } }
                                        ) {
                                            composable("playlist_list") {
                                                PlaylistListScreen(
                                                    viewModel = viewModel,
                                                    onPlaylistClick = { playlistName ->
                                                        playlistNavController.navigate("song_list_playlist/$playlistName")
                                                    },
                                                    onSmartAlbumClick = { id ->
                                                        playlistNavController.navigate("smart_album/$id")
                                                    }
                                                )
                                            }
                                            composable(
                                                "smart_album/{albumId}",
                                                arguments = listOf(navArgument("albumId") { type = NavType.StringType })
                                            ) { backStackEntry ->
                                                val id = backStackEntry.arguments?.getString("albumId") ?: ""
                                                val album = smartAlbums.find { it.id == id }
                                                SongListScreen(
                                                    viewModel = viewModel,
                                                    title = album?.title ?: "",
                                                    songs = album?.songs ?: emptyList(),
                                                    onBack = { playlistNavController.popBackStack() }
                                                )
                                            }
                                            composable(
                                                "song_list_playlist/{playlistName}",
                                                arguments = listOf(navArgument("playlistName") { type = NavType.StringType })
                                            ) { backStackEntry ->
                                                val name = backStackEntry.arguments?.getString("playlistName") ?: ""
                                                val songs = remember(playlists, name) {
                                                    playlists.find { it.name == name }?.songs ?: emptyList()
                                                }
                                                SongListScreen(
                                                    viewModel = viewModel,
                                                    title = name,
                                                    songs = songs,
                                                    onBack = { playlistNavController.popBackStack() }
                                                )
                                            }
                                        }
                                        1 -> NavHost(
                                            navController = folderNavController,
                                            startDestination = "folder_list",
                                            enterTransition = { fadeIn() + slideInHorizontally { it / 2 } },
                                            exitTransition = { fadeOut() + slideOutHorizontally { -it / 2 } },
                                            popEnterTransition = { fadeIn() + slideInHorizontally { -it / 2 } },
                                            popExitTransition = { fadeOut() + slideOutHorizontally { it / 2 } }
                                        ) {
                                            composable("folder_list") {
                                                FolderListScreen(
                                                    viewModel = viewModel,
                                                    onFolderClick = { folderName ->
                                                        folderNavController.navigate("song_list_folder/$folderName")
                                                    }
                                                )
                                            }
                                            composable(
                                                "song_list_folder/{folderName}",
                                                arguments = listOf(navArgument("folderName") { type = NavType.StringType })
                                            ) { backStackEntry ->
                                                val name = backStackEntry.arguments?.getString("folderName") ?: ""
                                                val songs = remember(folders, name) {
                                                    folders.find { it.name == name }?.songs ?: emptyList()
                                                }
                                                SongListScreen(
                                                    viewModel = viewModel,
                                                    title = name,
                                                    songs = songs,
                                                    onBack = { folderNavController.popBackStack() }
                                                )
                                            }
                                        }
                                        2 -> SettingsScreen(viewModel = viewModel)
                                    }
                                }
                            }

                            if (showPlayer) {
                                ModalBottomSheet(
                                    onDismissRequest = { showPlayer = false },
                                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                                    containerColor = MaterialTheme.colorScheme.background,
                                    scrimColor = Color.Black.copy(alpha = 0.5f),
                                    dragHandle = null
                                ) {
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        AppBackground(backgroundImageUri, backgroundAlpha)
                                        PlayerScreen(
                                            viewModel = viewModel,
                                            onClose = { showPlayer = false }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "OPEN_PLAYER") {
            openPlayerAction = true
        }
    }
}

/**
 * Full-screen wallpaper.
 *
 * Alpha is handed to the image painter rather than applied through `graphicsLayer { alpha = ... }`.
 * A layer alpha below 1 forces the whole screen-sized image into an offscreen buffer that has to be
 * allocated and composited every frame; the painter applies it while drawing instead.
 */
@Composable
private fun AppBackground(uri: String?, alpha: Float) {
    if (uri == null) return
    AsyncImage(
        model = uri,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        alpha = alpha
    )
    // Only drawn when the image swings too much for a single text colour to cover — a half-black,
    // half-white wallpaper has no readable text colour until its range is compressed. Flat or faint
    // wallpapers get no scrim at all.
    val scrim = LocalWallpaperScrim.current
    if (scrim > 0f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = scrim))
        )
    }
}
