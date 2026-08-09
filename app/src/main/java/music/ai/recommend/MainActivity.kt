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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import music.ai.recommend.ui.*
import music.ai.recommend.ui.theme.AiMusicTheme

class MainActivity : ComponentActivity() {
    private var openPlayerAction by mutableStateOf(false)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        enableEdgeToEdge()
        setContent {
            AiMusicTheme {
                val viewModel: MusicViewModel = viewModel()
                val folders by viewModel.folders.collectAsState()
                val playlists by viewModel.playlists.collectAsState()
                val backgroundImageUri by viewModel.backgroundImageUri.collectAsState()
                val backgroundAlpha by viewModel.backgroundAlpha.collectAsState()
                
                val folderNavController = rememberNavController()
                val playlistNavController = rememberNavController()
                var showPlayer by remember { mutableStateOf(false) }
                val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
                val scope = rememberCoroutineScope()

                val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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

                var permissionsGranted by remember {
                    mutableStateOf(
                        permissions.all {
                            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
                        }
                    )
                }

                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { results ->
                    permissionsGranted = results.values.all { it }
                    if (permissionsGranted) {
                        viewModel.loadMusic()
                    }
                }

                LaunchedEffect(openPlayerAction) {
                    if (openPlayerAction) {
                        showPlayer = true
                        openPlayerAction = false
                    }
                }

                LaunchedEffect(permissionsGranted) {
                    if (permissionsGranted) {
                        viewModel.loadMusic()
                    }
                }

                if (!permissionsGranted) {
                    StartScreen(onGrantPermissions = {
                        launcher.launch(permissions)
                    })

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
                    Box(modifier = Modifier.fillMaxSize()) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background
                        ) {}

                        if (backgroundImageUri != null) {
                            AsyncImage(
                                model = backgroundImageUri,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { alpha = backgroundAlpha },
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        }

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
                                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = backgroundAlpha.coerceAtLeast(0.4f))
                                    ) {
                                        NavigationBarItem(
                                            icon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null) },
                                            label = { Text(androidx.compose.ui.res.stringResource(id = R.string.nav_playlists)) },
                                            selected = pagerState.currentPage == 0,
                                            onClick = { scope.launch { pagerState.animateScrollToPage(0) } }
                                        )
                                        NavigationBarItem(
                                            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                                            label = { Text(androidx.compose.ui.res.stringResource(id = R.string.nav_music)) },
                                            selected = pagerState.currentPage == 1,
                                            onClick = { scope.launch { pagerState.animateScrollToPage(1) } }
                                        )
                                        NavigationBarItem(
                                            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                            label = { Text(androidx.compose.ui.res.stringResource(id = R.string.nav_settings)) },
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
                                                    }
                                                )
                                            }
                                            composable(
                                                "song_list_playlist/{playlistName}",
                                                arguments = listOf(navArgument("playlistName") { type = NavType.StringType })
                                            ) { backStackEntry ->
                                                val name = backStackEntry.arguments?.getString("playlistName") ?: ""
                                                val playlist = playlists.find { it.name == name }
                                                SongListScreen(
                                                    viewModel = viewModel,
                                                    title = name,
                                                    songs = playlist?.songs ?: emptyList(),
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
                                                val folder = folders.find { it.name == name }
                                                SongListScreen(
                                                    viewModel = viewModel,
                                                    title = name,
                                                    songs = folder?.songs ?: emptyList(),
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
                                        if (backgroundImageUri != null) {
                                            AsyncImage(
                                                model = backgroundImageUri,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .graphicsLayer { alpha = backgroundAlpha },
                                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                            )
                                        }
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
