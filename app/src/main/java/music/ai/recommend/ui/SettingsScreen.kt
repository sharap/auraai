package music.ai.recommend.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import music.ai.recommend.MusicViewModel
import music.ai.recommend.ai.AudioModelVariant
import music.ai.recommend.ai.ModelAsset
import music.ai.recommend.ai.SmartAlbumClustering
import music.ai.recommend.R
import music.ai.recommend.ui.theme.LocalMutedOnSurface
import music.ai.recommend.ui.theme.LocalSurfaceScrim
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(viewModel: MusicViewModel) {
    val isScanning by viewModel.isScanning.collectAsState()
    val backgroundImageUri by viewModel.backgroundImageUri.collectAsState()
    val backgroundAlpha by viewModel.backgroundAlpha.collectAsState()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> viewModel.setBackgroundImage(uri) }
    )

    LaunchedEffect(Unit) { viewModel.refreshModelStatus() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        item {
            SectionTitle(stringResource(id = R.string.library))
            SettingsCard {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = stringResource(id = R.string.refresh_library),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(id = R.string.scan_device_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        IconButton(onClick = { viewModel.loadMusic() }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(id = R.string.refresh_library))
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            SectionTitle(stringResource(id = R.string.appearance))
            SettingsCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(id = R.string.background_image),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (backgroundImageUri != null) stringResource(id = R.string.custom_background_active)
                                else stringResource(id = R.string.default_background),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Row {
                            if (backgroundImageUri != null) {
                                IconButton(onClick = { viewModel.setBackgroundImage(null) }) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(id = R.string.delete))
                                }
                            }
                            IconButton(onClick = {
                                launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }) {
                                Icon(Icons.Default.Image, contentDescription = stringResource(id = R.string.background_image))
                            }
                        }
                    }

                    if (backgroundImageUri != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(id = R.string.transparency),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Slider(
                            value = backgroundAlpha,
                            onValueChange = { viewModel.setBackgroundAlpha(it) },
                            valueRange = 0.05f..1f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            SectionTitle(stringResource(id = R.string.ai_models))
            AiModelsCard(viewModel)
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            SectionTitle(stringResource(id = R.string.ai_analysis))
            AiScanCard(viewModel)
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            SectionTitle(stringResource(id = R.string.smart_albums))
            SmartAlbumsCard(viewModel)
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            SectionTitle(stringResource(id = R.string.playback))
            PlaybackCard(viewModel)
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            SectionTitle(stringResource(id = R.string.equalizer))
            EqualizerControl(viewModel)
        }

        // Keeps the last card clear of the navigation bar.
        item { Spacer(modifier = Modifier.height(100.dp)) }
    }
}

/** Behaviour that has to survive the app being in the background, so it lives in the service. */
@Composable
private fun PlaybackCard(viewModel: MusicViewModel) {
    val pauseOnDisconnect by viewModel.pauseOnDisconnect.collectAsState()
    val handleAudioFocus by viewModel.handleAudioFocus.collectAsState()

    SettingsCard {
        Column(modifier = Modifier.padding(16.dp)) {
            SwitchRow(
                title = stringResource(id = R.string.pause_on_disconnect),
                hint = stringResource(id = R.string.pause_on_disconnect_hint),
                checked = pauseOnDisconnect,
                onCheckedChange = { viewModel.setPauseOnDisconnect(it) }
            )
            Spacer(modifier = Modifier.height(16.dp))
            SwitchRow(
                title = stringResource(id = R.string.handle_audio_focus),
                hint = stringResource(id = R.string.handle_audio_focus_hint),
                checked = handleAudioFocus,
                onCheckedChange = { viewModel.setHandleAudioFocus(it) }
            )
        }
    }
}

/**
 * The DBSCAN radius, as a multiplier on the one chosen automatically: an absolute eps is picked
 * per group in that group's own reduced space, so no single number would mean the same thing in
 * two libraries.
 */
@Composable
private fun SmartAlbumsCard(viewModel: MusicViewModel) {
    val scale by viewModel.smartAlbumsEpsScale.collectAsState()
    val building by viewModel.smartAlbumsBuilding.collectAsState()
    // Regrouping takes seconds on a large library, so it runs when the thumb is released, not on
    // every step of the drag.
    var dragged by remember(scale) { mutableFloatStateOf(scale) }

    SettingsCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(id = R.string.smart_albums_eps),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (building) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = if (dragged == 1f) stringResource(id = R.string.smart_albums_eps_auto)
                    else "×" + String.format(java.util.Locale.ROOT, "%.2f", dragged),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = stringResource(id = R.string.smart_albums_eps_hint),
                style = MaterialTheme.typography.bodySmall,
                color = LocalMutedOnSurface.current
            )
            Slider(
                value = dragged,
                onValueChange = { dragged = (it * 20).roundToInt() / 20f },
                onValueChangeFinished = { viewModel.setSmartAlbumsEpsScale(dragged) },
                valueRange = SmartAlbumClustering.MIN_EPS_SCALE..SmartAlbumClustering.MAX_EPS_SCALE,
                // 0.05 per step.
                steps = ((SmartAlbumClustering.MAX_EPS_SCALE - SmartAlbumClustering.MIN_EPS_SCALE) * 20).roundToInt() - 1,
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(id = R.string.smart_albums_eps_tighter),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalMutedOnSurface.current,
                    modifier = Modifier.weight(1f)
                )
                if (scale != 1f) {
                    TextButton(onClick = { viewModel.setSmartAlbumsEpsScale(1f) }) {
                        Text(stringResource(id = R.string.smart_albums_eps_reset))
                    }
                }
                Text(
                    text = stringResource(id = R.string.smart_albums_eps_wider),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalMutedOnSurface.current,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = LocalMutedOnSurface.current
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            // Opacity comes from the theme rather than a constant: a bright or busy wallpaper at
            // high opacity needs more covering before text on the card reads.
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalSurfaceScrim.current)
        ),
        content = content
    )
}

/** Where the CLAP weights are, which export is in use, and how to fetch or remove them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiModelsCard(viewModel: MusicViewModel) {
    val status by viewModel.modelStatus.collectAsState()
    val progress by viewModel.modelProgress.collectAsState()
    val analysedWith by viewModel.embeddingsVariant.collectAsState()

    SettingsCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(id = R.string.model_variant_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VariantChip(
                    label = stringResource(
                        id = R.string.model_variant_fast,
                        formatBytes(ModelAsset.AUDIO_MODEL_QUANTIZED.sizeBytes)
                    ),
                    selected = status.variant == AudioModelVariant.QUANTIZED,
                    onSelect = { viewModel.selectAudioVariant(AudioModelVariant.QUANTIZED) }
                )
                VariantChip(
                    label = stringResource(
                        id = R.string.model_variant_full,
                        formatBytes(ModelAsset.AUDIO_MODEL.sizeBytes)
                    ),
                    selected = status.variant == AudioModelVariant.FULL,
                    onSelect = { viewModel.selectAudioVariant(AudioModelVariant.FULL) }
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    id = if (status.variant == AudioModelVariant.QUANTIZED) R.string.model_variant_fast_hint
                    else R.string.model_variant_full_hint
                ),
                style = MaterialTheme.typography.bodySmall,
                color = LocalMutedOnSurface.current
            )

            // Worth mentioning, not worth alarming over: the two exports were measured to agree
            // to a cosine of 0.9985 on the same audio, so mixed results are barely distinguishable.
            if (analysedWith != null && analysedWith != status.variant) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(id = R.string.model_variant_mismatch),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalMutedOnSurface.current
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            ModelRow(
                label = stringResource(id = R.string.model_audio),
                bundled = status.audioBundled,
                ready = status.audioReady
            )
            Spacer(modifier = Modifier.height(8.dp))
            ModelRow(
                label = stringResource(id = R.string.model_text),
                bundled = status.textBundled,
                ready = status.textReady
            )

            Spacer(modifier = Modifier.height(16.dp))

            when {
                progress.running -> {
                    Text(
                        text = stringResource(
                            id = R.string.model_downloading_file,
                            progress.currentFile ?: "",
                            formatBytes(progress.bytesDone),
                            formatBytes(progress.bytesTotal)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { viewModel.cancelModelDownload() },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(stringResource(id = R.string.cancel_download))
                    }
                }

                status.pendingBytes > 0L -> {
                    if (progress.error != null) {
                        Text(
                            text = stringResource(id = R.string.model_download_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(
                        text = stringResource(id = R.string.model_download_hint, formatBytes(status.pendingBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.downloadModels() },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(id = R.string.download_models))
                    }
                }

                else -> {
                    Text(
                        text = stringResource(id = R.string.models_ready),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            // Only offered when something was actually downloaded; bundled weights live in the APK
            // and cannot be freed.
            val hasDownloads = (status.quantizedReady && !status.quantizedBundled) ||
                (status.fullReady && !status.fullBundled) ||
                (status.textReady && !status.textBundled)
            if (hasDownloads) {
                TextButton(
                    onClick = { viewModel.deleteDownloadedModels() },
                    modifier = Modifier.align(Alignment.Start)
                ) {
                    Text(
                        text = stringResource(id = R.string.delete_downloaded_models),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VariantChip(label: String, selected: Boolean, onSelect: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onSelect,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else null
    )
}

@Composable
private fun ModelRow(label: String, bundled: Boolean, ready: Boolean) {
    val (icon, tint, state) = when {
        bundled -> Triple(
            Icons.Default.Inventory2,
            MaterialTheme.colorScheme.secondary,
            stringResource(id = R.string.model_state_bundled)
        )
        ready -> Triple(
            Icons.Default.CheckCircle,
            MaterialTheme.colorScheme.secondary,
            stringResource(id = R.string.model_state_downloaded)
        )
        else -> Triple(
            Icons.Default.CloudOff,
            MaterialTheme.colorScheme.error,
            stringResource(id = R.string.model_state_missing)
        )
    }

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(text = state, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

@Composable
private fun AiScanCard(viewModel: MusicViewModel) {
    val isAiScanning by viewModel.isAiScanning.collectAsState()
    val aiScanProgress by viewModel.aiScanProgress.collectAsState()
    val aiScanStatus by viewModel.aiScanStatus.collectAsState()
    val scannedSongIds by viewModel.scannedSongIds.collectAsState()
    val analysisReset by viewModel.analysisReset.collectAsState()

    SettingsCard {
        Column(modifier = Modifier.padding(16.dp)) {
            if (analysisReset) {
                Text(
                    text = stringResource(id = R.string.analysis_reset),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(
                text = stringResource(id = R.string.analyzed_songs, scannedSongIds.size),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (isAiScanning) {
                Text(
                    text = aiScanStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { aiScanProgress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.stopAiScan() },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(id = R.string.stop_ai_scan))
                }
            } else {
                Text(
                    text = aiScanStatus.ifEmpty { stringResource(id = R.string.ai_scan_hint) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.startAiScan() },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(id = R.string.start_ai_scan))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = { viewModel.clearAiData() },
                modifier = Modifier.align(Alignment.Start)
            ) {
                Text(stringResource(id = R.string.clear_ai_data), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun EqualizerControl(viewModel: MusicViewModel) {
    val eqBands by viewModel.eqBands.collectAsState()
    val eqRange by viewModel.eqRange.collectAsState()
    val eqPresets by viewModel.eqPresets.collectAsState()
    val eqEnabled by viewModel.eqEnabled.collectAsState()

    var showSavePresetDialog by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }

    if (eqBands.isEmpty()) {
        Text(stringResource(id = R.string.eq_not_available), style = MaterialTheme.typography.bodySmall)
        return
    }

    Column {
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(id = R.string.eq_enabled),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Switch(
                        checked = eqEnabled,
                        onCheckedChange = { viewModel.setEqEnabled(it) }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    eqBands.forEach { band ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            Text(
                                text = "${band.level / 100}dB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.height(20.dp)
                            )
                            VerticalSlider(
                                value = band.level.toFloat(),
                                onValueChange = { viewModel.setEqBandLevel(band.index, it.toInt()) },
                                valueRange = eqRange.first.toFloat()..eqRange.last.toFloat(),
                                enabled = eqEnabled,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = if (band.freq >= 1000000) "${band.freq / 1000000}k" else "${band.freq / 1000}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.height(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(onClick = { viewModel.resetEqualizer() }, enabled = eqEnabled) {
                        Text(stringResource(id = R.string.reset))
                    }
                    Button(onClick = { showSavePresetDialog = true }, enabled = eqEnabled) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(id = R.string.save_preset))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(id = R.string.presets),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(eqPresets, key = { it.name }) { preset ->
                InputChip(
                    selected = false,
                    enabled = eqEnabled,
                    onClick = { viewModel.applyEqPreset(preset) },
                    label = { Text(preset.name) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(id = R.string.delete),
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { viewModel.deleteEqPreset(preset) }
                        )
                    }
                )
            }
        }
    }

    if (showSavePresetDialog) {
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            title = { Text(stringResource(id = R.string.save_preset)) },
            text = {
                OutlinedTextField(
                    value = newPresetName,
                    onValueChange = { newPresetName = it },
                    label = { Text(stringResource(id = R.string.preset_name)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newPresetName.isNotBlank()) {
                        viewModel.saveEqPreset(newPresetName)
                        showSavePresetDialog = false
                        newPresetName = ""
                    }
                }) {
                    Text(stringResource(id = R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSavePresetDialog = false }) {
                    Text(stringResource(id = R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f
) {
    Box(
        modifier = modifier
            .width(32.dp)
            .graphicsLayer { rotationZ = 270f }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints(
                        minWidth = constraints.minHeight,
                        maxWidth = constraints.maxHeight,
                        minHeight = constraints.minWidth,
                        maxHeight = constraints.maxWidth
                    )
                )
                layout(placeable.height, placeable.width) {
                    placeable.place(
                        -((placeable.width - placeable.height) / 2),
                        -((placeable.height - placeable.width) / 2)
                    )
                }
            }
    ) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
