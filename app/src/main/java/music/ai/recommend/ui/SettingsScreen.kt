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
import music.ai.recommend.R

@Composable
fun SettingsScreen(viewModel: MusicViewModel) {
    val isScanning by viewModel.isScanning.collectAsState()
    val backgroundImageUri by viewModel.backgroundImageUri.collectAsState()
    val backgroundAlpha by viewModel.backgroundAlpha.collectAsState()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            viewModel.setBackgroundImage(uri)
        }
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        item {
            Text(
                text = stringResource(id = R.string.library),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                )
            ) {
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
                        Text(stringResource(id = R.string.scan_device_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
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
            Text(
                text = stringResource(id = R.string.appearance),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                )
            ) {
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
                                text = if (backgroundImageUri != null) stringResource(id = R.string.custom_background_active) else stringResource(id = R.string.default_background),
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
            Text(
                text = stringResource(id = R.string.ai_analysis),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val isAiScanning by viewModel.isAiScanning.collectAsState()
                    val aiScanProgress by viewModel.aiScanProgress.collectAsState()
                    val aiScanStatus by viewModel.aiScanStatus.collectAsState()
                    val scannedSongIds by viewModel.scannedSongIds.collectAsState()

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
                            text = if (aiScanStatus.isNotEmpty()) aiScanStatus else stringResource(id = R.string.ai_scan_hint),
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
                        Text("Clear AI Data", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(id = R.string.equalizer),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            EqualizerControl(viewModel)
        }
        
        // Add a bottom spacer so the content isn't covered by navigation bars
        item {
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@Composable
fun EqualizerControl(viewModel: MusicViewModel) {
    val eqBands by viewModel.eqBands.collectAsState()
    val eqRange by viewModel.eqRange.collectAsState()
    val eqPresets by viewModel.eqPresets.collectAsState()
    
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }

    if (eqBands.isEmpty()) {
        Text(stringResource(id = R.string.eq_not_available), style = MaterialTheme.typography.bodySmall)
    } else {
        Column {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
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
                        Button(onClick = { viewModel.resetEqualizer() }) {
                            Text(stringResource(id = R.string.reset))
                        }
                        Button(onClick = { showSavePresetDialog = true }) {
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
                items(eqPresets) { preset ->
                    InputChip(
                        selected = false,
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
}

@Composable
fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f
) {
    Box(
        modifier = modifier
            .width(32.dp)
            .graphicsLayer {
                rotationZ = 270f
            }
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
                    placeable.place(-((placeable.width - placeable.height) / 2), -((placeable.height - placeable.width) / 2))
                }
            }
    ) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
