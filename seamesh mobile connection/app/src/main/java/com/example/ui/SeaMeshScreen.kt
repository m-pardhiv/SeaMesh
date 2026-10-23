package com.example.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.BleConnectionStatus
import com.example.state.SeaMeshUiState
import com.example.ui.theme.AbyssalKelp
import com.example.ui.theme.AquamarineSwell
import com.example.ui.theme.CoralBuoy
import com.example.ui.theme.CoralBuoyBg
import com.example.ui.theme.DriftwoodCream
import com.example.ui.theme.HarborJade
import com.example.ui.theme.HarborJadeBg
import com.example.ui.theme.LagoonTealDim
import com.example.ui.theme.LighthouseGold
import com.example.ui.theme.LighthouseGoldBg
import com.example.ui.theme.NauticalMonoFamily
import com.example.ui.theme.SeaGlassBorder
import com.example.ui.theme.SeaGlassElevated
import com.example.ui.theme.SeaGlassSurface
import com.example.ui.theme.SeaMistMuted
import com.example.ui.theme.SeafoamCrest
import com.example.ui.theme.ShallowReef
import com.example.ui.theme.SunlitSand
import com.example.ui.theme.TidalLagoon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SeaMeshScreen(
    uiState: SeaMeshUiState,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    onToggleActive: (Boolean) -> Unit,
    onChangeDeviceClick: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onOpenGpsSettings: () -> Unit,
    onSaveSettings: (serverUrl: String, apiKey: String) -> Unit
) {
    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current

    var serverUrlInput by remember(uiState.serverUrl) { mutableStateOf(uiState.serverUrl) }
    var apiKeyInput by remember(uiState.apiKey) { mutableStateOf(uiState.apiKey) }
    var passwordVisible by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            TidalLagoon,
                            AbyssalKelp,
                            Color(0xFF041513)
                        )
                    )
                )
                .drawBehind {
                    // Subtle bathymetric sea chart contour curves across the background
                    val contourColor = SeafoamCrest.copy(alpha = 0.045f)
                    val w = size.width
                    val h = size.height
                    for (i in 1..6) {
                        val yBase = h * (0.22f + i * 0.13f)
                        val path = Path().apply {
                            moveTo(0f, yBase)
                            cubicTo(
                                w * 0.3f, yBase - 40f * (i % 2 * 2 - 1),
                                w * 0.7f, yBase + 45f * (i % 2 * 2 - 1),
                                w, yBase - 15f
                            )
                        }
                        drawPath(
                            path = path,
                            color = contourColor,
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = innerPadding.calculateBottomPadding())
                    .verticalScroll(scrollState)
            ) {
                // Animated Seascape Hero Header with rolling ocean swells & fishing boat beacon
                OceanWaveHeroBanner(
                    isActive = uiState.isActive,
                    connectionStatus = uiState.connectionStatus,
                    isOnline = uiState.isOnline
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 1. Permissions Warning Banner
                    if (!hasPermissions) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = LighthouseGoldBg),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, LighthouseGold, RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Security,
                                        contentDescription = "Security Alert",
                                        tint = LighthouseGold,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Permissions Required",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = LighthouseGold
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "SeaMesh requires Location and Bluetooth permissions to link with your boat's ESP32 unit and broadcast GPS coordinates.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SunlitSand
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = onRequestPermissions,
                                    modifier = Modifier.testTag("permissions_request_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = LighthouseGold),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(
                                        text = "Grant Permissions",
                                        color = AbyssalKelp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // 2. Hardware Warnings (Bluetooth / GPS off)
                    if (!uiState.isBluetoothEnabled) {
                        HardwareWarningCard(
                            icon = Icons.Default.BluetoothDisabled,
                            title = "Bluetooth is Off",
                            message = "Turn on Bluetooth to communicate with your boat's ESP32 SeaMesh unit.",
                            buttonText = "Open Bluetooth Settings",
                            testTag = "bluetooth_settings_button",
                            onClick = onOpenBluetoothSettings
                        )
                    }

                    if (!uiState.isGpsEnabled) {
                        HardwareWarningCard(
                            icon = Icons.Default.GpsOff,
                            title = "GPS is Off",
                            message = "Turn on Location / GPS to provide coordinates to your boat's LoRa mesh.",
                            buttonText = "Open Location Settings",
                            testTag = "gps_settings_button",
                            onClick = onOpenGpsSettings
                        )
                    }

                    // 3. MASTER SWITCH CARD: "SeaMesh active"
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (uiState.isActive) SeaGlassElevated else SeaGlassSurface
                        ),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = if (uiState.isActive) 1.5.dp else 1.dp,
                                brush = if (uiState.isActive) {
                                    Brush.horizontalGradient(
                                        listOf(SeafoamCrest, CoralBuoy)
                                    )
                                } else {
                                    SolidColor(SeaGlassBorder)
                                },
                                shape = RoundedCornerShape(20.dp)
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                // Tactile buoy indicator dot
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (uiState.isActive) SeafoamCrest else SeaMistMuted.copy(alpha = 0.4f)
                                        )
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column {
                                    Text(
                                        text = "SeaMesh active",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = SunlitSand
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = if (uiState.isActive)
                                            "Streaming boat GPS every 5s & relaying LoRa mesh"
                                        else
                                            "Switch on to engage boat transponder link",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (uiState.isActive) SeafoamCrest else DriftwoodCream
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Switch(
                                checked = uiState.isActive,
                                onCheckedChange = { onToggleActive(it) },
                                modifier = Modifier.testTag("active_switch"),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = AbyssalKelp,
                                    checkedTrackColor = SeafoamCrest,
                                    uncheckedThumbColor = DriftwoodCream,
                                    uncheckedTrackColor = AbyssalKelp,
                                    uncheckedBorderColor = SeaGlassBorder
                                )
                            )
                        }
                    }

                    // 4. STATUS CARD (All required status lines in a Sea-Glass Marine Instrument panel)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SeaGlassSurface.copy(alpha = 0.92f)),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, SeaGlassBorder, RoundedCornerShape(20.dp))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "BRIDGE TELEMETRY & MESH LINK",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = SeafoamCrest,
                                    letterSpacing = 1.2.sp
                                )
                                Text(
                                    text = "CH-LORA",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = NauticalMonoFamily,
                                    color = SeaMistMuted
                                )
                            }

                            // Line A: Device
                            StatusLineDevice(
                                savedName = uiState.savedDeviceName,
                                savedAddress = uiState.savedDeviceAddress,
                                status = uiState.connectionStatus,
                                onChangeDevice = onChangeDeviceClick
                            )

                            HorizontalDivider(color = SeaGlassBorder.copy(alpha = 0.45f))

                            // Line B: GPS
                            StatusLineGps(
                                gpsStatus = uiState.gpsStatus,
                                isFixPresent = uiState.latestLat != null
                            )

                            HorizontalDivider(color = SeaGlassBorder.copy(alpha = 0.45f))

                            // Line C: Internet
                            StatusLineInternet(
                                isOnline = uiState.isOnline
                            )

                            HorizontalDivider(color = SeaGlassBorder.copy(alpha = 0.45f))

                            // Line D: Last upload
                            StatusLineUpload(
                                lastUploadText = uiState.lastUploadText
                            )

                            HorizontalDivider(color = SeaGlassBorder.copy(alpha = 0.45f))

                            // Line E: Queue
                            StatusLineQueue(
                                queueSize = uiState.queueSize
                            )
                        }
                    }

                    // 5. GATEWAY SETTINGS CARD
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SeaGlassSurface.copy(alpha = 0.92f)),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, SeaGlassBorder, RoundedCornerShape(20.dp))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Settings",
                                    tint = CoralBuoy,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "SHORE SERVER CONFIGURATION",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = SeafoamCrest,
                                    letterSpacing = 1.2.sp
                                )
                            }

                            // Server URL
                            OutlinedTextField(
                                value = serverUrlInput,
                                onValueChange = {
                                    serverUrlInput = it
                                    onSaveSettings(it, apiKeyInput)
                                },
                                label = { Text("Server URL") },
                                placeholder = { Text("https://seamesh-api.onrender.com") },
                                singleLine = true,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Link,
                                        contentDescription = "URL",
                                        tint = AquamarineSwell
                                    )
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Next
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("server_url_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = SeafoamCrest,
                                    unfocusedBorderColor = SeaGlassBorder,
                                    focusedTextColor = SunlitSand,
                                    unfocusedTextColor = SunlitSand,
                                    focusedContainerColor = AbyssalKelp.copy(alpha = 0.55f),
                                    unfocusedContainerColor = AbyssalKelp.copy(alpha = 0.55f),
                                    focusedLabelColor = SeafoamCrest,
                                    unfocusedLabelColor = DriftwoodCream
                                )
                            )

                            // API Key
                            OutlinedTextField(
                                value = apiKeyInput,
                                onValueChange = {
                                    apiKeyInput = it
                                    onSaveSettings(serverUrlInput, it)
                                },
                                label = { Text("API key") },
                                placeholder = { Text("Enter gateway API key") },
                                singleLine = true,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Key,
                                        contentDescription = "API Key",
                                        tint = CoralBuoy
                                    )
                                },
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(
                                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = if (passwordVisible) "Hide key" else "Show key",
                                            tint = SeaMistMuted
                                        )
                                    }
                                },
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = { focusManager.clearFocus() }
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("api_key_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = SeafoamCrest,
                                    unfocusedBorderColor = SeaGlassBorder,
                                    focusedTextColor = SunlitSand,
                                    unfocusedTextColor = SunlitSand,
                                    focusedContainerColor = AbyssalKelp.copy(alpha = 0.55f),
                                    unfocusedContainerColor = AbyssalKelp.copy(alpha = 0.55f),
                                    focusedLabelColor = SeafoamCrest,
                                    unfocusedLabelColor = DriftwoodCream
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun OceanWaveHeroBanner(
    isActive: Boolean,
    connectionStatus: BleConnectionStatus,
    isOnline: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ocean_swell")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isActive) 4200 else 7500,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )

    val beaconPulse by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "beacon_pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1E2D2B), // Coastal dusk horizon with warm salt haze
                        Color(0xFF0E3832),
                        TidalLagoon
                    )
                )
            )
    ) {
        // Animated Ocean Swells, Horizon Sun/Beacon, and Fishing Vessel Silhouette
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(185.dp)
        ) {
            val w = size.width
            val h = size.height

            // Warm coastal horizon sun / buoy glow on the right
            val sunCenter = Offset(w * 0.78f, h * 0.42f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        CoralBuoy.copy(alpha = 0.32f),
                        LighthouseGold.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    center = sunCenter,
                    radius = w * 0.42f
                ),
                center = sunCenter,
                radius = w * 0.42f
            )

            // Back swell (deep kelp green-teal)
            val backWavePath = Path().apply {
                moveTo(0f, h)
                val baseHeight = h * 0.60f
                val step = 16f
                var x = 0f
                while (x <= w + step) {
                    val angle = (x / w) * 2.2f * PI.toFloat() + wavePhase
                    val y = baseHeight + sin(angle) * 10.dp.toPx()
                    lineTo(x, y)
                    x += step
                }
                lineTo(w, h)
                close()
            }
            drawPath(
                path = backWavePath,
                color = Color(0xFF124A42)
            )

            // Mid swell (tropical reef turquoise)
            val midWavePath = Path().apply {
                moveTo(0f, h)
                val baseHeight = h * 0.71f
                val step = 16f
                var x = 0f
                while (x <= w + step) {
                    val angle = (x / w) * 1.8f * PI.toFloat() - wavePhase * 0.85f + 1.1f
                    val y = baseHeight + sin(angle) * 12.dp.toPx()
                    lineTo(x, y)
                    x += step
                }
                lineTo(w, h)
                close()
            }
            drawPath(
                path = midWavePath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        LagoonTealDim.copy(alpha = 0.55f),
                        ShallowReef
                    ),
                    startY = h * 0.6f,
                    endY = h
                )
            )

            // Fishing Boat riding the mid swell at 76% width
            val boatX = w * 0.76f
            val boatWaveAngle = (0.76f) * 1.8f * PI.toFloat() - wavePhase * 0.85f + 1.1f
            val boatY = h * 0.71f + sin(boatWaveAngle) * 12.dp.toPx() - 4.dp.toPx()

            // Concentric LoRa mesh signal ripples from boat mast when active
            val mastTop = Offset(boatX, boatY - 26.dp.toPx())
            if (isActive) {
                val maxRadius = 46.dp.toPx()
                drawCircle(
                    color = SeafoamCrest.copy(alpha = (1f - beaconPulse) * 0.65f),
                    radius = maxRadius * beaconPulse,
                    center = mastTop,
                    style = Stroke(width = 2.dp.toPx())
                )
                val secondPulse = (beaconPulse + 0.5f) % 1f
                drawCircle(
                    color = CoralBuoy.copy(alpha = (1f - secondPulse) * 0.55f),
                    radius = maxRadius * secondPulse,
                    center = mastTop,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }

            // Boat Hull
            val hullPath = Path().apply {
                moveTo(boatX - 24.dp.toPx(), boatY - 2.dp.toPx())
                lineTo(boatX + 26.dp.toPx(), boatY - 4.dp.toPx())
                lineTo(boatX + 19.dp.toPx(), boatY + 8.dp.toPx())
                lineTo(boatX - 18.dp.toPx(), boatY + 8.dp.toPx())
                close()
            }
            drawPath(path = hullPath, color = SunlitSand)

            // Boat Cabin / Wheelhouse
            val cabinPath = Path().apply {
                moveTo(boatX - 10.dp.toPx(), boatY - 2.dp.toPx())
                lineTo(boatX - 8.dp.toPx(), boatY - 12.dp.toPx())
                lineTo(boatX + 8.dp.toPx(), boatY - 12.dp.toPx())
                lineTo(boatX + 11.dp.toPx(), boatY - 2.dp.toPx())
                close()
            }
            drawPath(path = cabinPath, color = DriftwoodCream)

            // Mast & Beacon
            drawLine(
                color = SunlitSand,
                start = Offset(boatX, boatY - 12.dp.toPx()),
                end = mastTop,
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(
                color = if (isActive) CoralBuoy else SeaMistMuted,
                radius = 4.dp.toPx(),
                center = mastTop
            )

            // Foreground swell & luminous seafoam crest line
            val frontWavePath = Path()
            val crestLinePath = Path()
            val frontBase = h * 0.83f
            val step = 14f
            var x = 0f
            frontWavePath.moveTo(0f, h)
            while (x <= w + step) {
                val angle = (x / w) * 2.5f * PI.toFloat() + wavePhase * 1.15f + 2.4f
                val y = frontBase + cos(angle) * 9.dp.toPx()
                frontWavePath.lineTo(x, y)
                if (x == 0f) {
                    crestLinePath.moveTo(x, y)
                } else {
                    crestLinePath.lineTo(x, y)
                }
                x += step
            }
            frontWavePath.lineTo(w, h)
            frontWavePath.close()

            drawPath(
                path = frontWavePath,
                color = TidalLagoon
            )
            drawPath(
                path = crestLinePath,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        SeafoamCrest.copy(alpha = 0.35f),
                        SeafoamCrest.copy(alpha = 0.85f),
                        CoralBuoy.copy(alpha = 0.55f),
                        SeafoamCrest.copy(alpha = 0.4f)
                    )
                ),
                style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // Top Title & Nautical Status Overlay respecting status bar insets
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(CoralBuoy.copy(alpha = 0.22f))
                                .border(1.dp, CoralBuoy.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "MARITIME MESH",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = NauticalMonoFamily,
                                fontWeight = FontWeight.Bold,
                                color = CoralBuoy,
                                fontSize = 10.sp
                            )
                        }

                        Text(
                            text = if (isOnline) "SHORE UPLINK" else "LORA RELAY",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = NauticalMonoFamily,
                            color = SeafoamCrest,
                            fontSize = 10.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "SeaMesh",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = SunlitSand
                    )
                    Text(
                        text = "Coastal GPS & LoRa Emergency Bridge",
                        style = MaterialTheme.typography.bodySmall,
                        color = DriftwoodCream
                    )
                }

                // Active / Standby Beacon Pill
                val pillColor = if (isActive) SeafoamCrest else SeaMistMuted
                val pillBg = if (isActive) SeaGlassElevated.copy(alpha = 0.9f) else AbyssalKelp.copy(alpha = 0.7f)
                val pillText = if (isActive) "ACTIVE" else "STANDBY"

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(pillBg)
                        .border(1.dp, pillColor.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isActive) CoralBuoy else SeaMistMuted)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = pillText,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = NauticalMonoFamily,
                        fontWeight = FontWeight.Bold,
                        color = pillColor
                    )
                }
            }
        }
    }
}

@Composable
private fun HardwareWarningCard(
    icon: ImageVector,
    title: String,
    message: String,
    buttonText: String,
    testTag: String,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CoralBuoyBg),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CoralBuoy.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = CoralBuoy,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = CoralBuoy
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = SunlitSand
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onClick,
                modifier = Modifier.testTag(testTag),
                colors = ButtonDefaults.buttonColors(containerColor = CoralBuoy),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = buttonText,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun StatusLineDevice(
    savedName: String?,
    savedAddress: String?,
    status: BleConnectionStatus,
    onChangeDevice: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AbyssalKelp.copy(alpha = 0.6f))
                    .border(1.dp, SeaGlassBorder, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Bluetooth,
                    contentDescription = "Device",
                    tint = SeafoamCrest,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Boat ESP32 Unit",
                    style = MaterialTheme.typography.labelSmall,
                    color = SeaMistMuted
                )
                Text(
                    text = savedName ?: "No device configured",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = SunlitSand
                )
                if (!savedAddress.isNullOrBlank()) {
                    Text(
                        text = savedAddress,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = NauticalMonoFamily,
                        color = DriftwoodCream,
                        fontSize = 11.sp
                    )
                }

                // Connection badge: "Connected" / "Connecting..." / "Not found"
                val (badgeText, badgeBg, badgeTextColor) = when (status) {
                    BleConnectionStatus.CONNECTED -> Triple("Connected", HarborJadeBg, HarborJade)
                    BleConnectionStatus.CONNECTING -> Triple("Connecting...", LighthouseGoldBg, LighthouseGold)
                    BleConnectionStatus.NOT_FOUND -> Triple("Not found", CoralBuoyBg, CoralBuoy)
                    BleConnectionStatus.DISCONNECTED -> Triple("Disconnected", AbyssalKelp.copy(alpha = 0.7f), SeaMistMuted)
                }

                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeBg)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = badgeTextColor
                    )
                }
            }
        }

        OutlinedButton(
            onClick = onChangeDevice,
            modifier = Modifier.testTag("change_device_button"),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = SeafoamCrest),
            border = ButtonDefaults.outlinedButtonBorder.copy(brush = SolidColor(SeaGlassBorder))
        ) {
            Text(
                text = "Change device",
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun StatusLineGps(
    gpsStatus: String,
    isFixPresent: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AbyssalKelp.copy(alpha = 0.6f))
                .border(1.dp, SeaGlassBorder, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isFixPresent) Icons.Default.GpsFixed else Icons.Default.GpsNotFixed,
                contentDescription = "GPS",
                tint = if (isFixPresent) HarborJade else LighthouseGold,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = "Vessel Coordinates (GPS_PROVIDER)",
                style = MaterialTheme.typography.labelSmall,
                color = SeaMistMuted
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = gpsStatus,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isFixPresent) FontWeight.Medium else FontWeight.Normal,
                fontFamily = if (isFixPresent) NauticalMonoFamily else MaterialTheme.typography.bodyMedium.fontFamily,
                color = if (isFixPresent) SunlitSand else LighthouseGold
            )
        }
    }
}

@Composable
private fun StatusLineInternet(
    isOnline: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AbyssalKelp.copy(alpha = 0.6f))
                .border(1.dp, SeaGlassBorder, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isOnline) Icons.Default.Cloud else Icons.Default.CloudOff,
                contentDescription = "Internet",
                tint = if (isOnline) SeafoamCrest else DriftwoodCream,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = "Shore Internet Link",
                style = MaterialTheme.typography.labelSmall,
                color = SeaMistMuted
            )
            Spacer(modifier = Modifier.height(2.dp))
            val internetText = if (isOnline) {
                "Online - this boat forwards alerts"
            } else {
                "Offline - alerts go over LoRa"
            }
            Text(
                text = internetText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isOnline) HarborJade else DriftwoodCream
            )
        }
    }
}

@Composable
private fun StatusLineUpload(
    lastUploadText: String
) {
    val isError = lastUploadText.contains("failed", ignoreCase = true) ||
            lastUploadText.contains("error", ignoreCase = true)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AbyssalKelp.copy(alpha = 0.6f))
                .border(1.dp, SeaGlassBorder, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CloudUpload,
                contentDescription = "Upload",
                tint = if (isError) CoralBuoy else SeafoamCrest,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = "Last Upload",
                style = MaterialTheme.typography.labelSmall,
                color = SeaMistMuted
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = lastUploadText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Normal,
                color = if (isError) CoralBuoy else SunlitSand
            )
        }
    }
}

@Composable
private fun StatusLineQueue(
    queueSize: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AbyssalKelp.copy(alpha = 0.6f))
                .border(1.dp, SeaGlassBorder, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Queue",
                tint = if (queueSize > 0) CoralBuoy else SeaMistMuted,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = "Packet Hold Queue",
                style = MaterialTheme.typography.labelSmall,
                color = SeaMistMuted
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (queueSize == 0)
                    "0 packets waiting to upload"
                else
                    "$queueSize packet(s) waiting to upload (retrying every 5s)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (queueSize > 0) FontWeight.Bold else FontWeight.Normal,
                color = if (queueSize > 0) LighthouseGold else DriftwoodCream
            )
        }
    }
}
