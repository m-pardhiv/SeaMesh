package com.example.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.DiscoveredDevice
import com.example.ui.theme.AbyssalKelp
import com.example.ui.theme.CoralBuoy
import com.example.ui.theme.DriftwoodCream
import com.example.ui.theme.NauticalMonoFamily
import com.example.ui.theme.SeaGlassBorder
import com.example.ui.theme.SeaGlassElevated
import com.example.ui.theme.SeaGlassSurface
import com.example.ui.theme.SeaMistMuted
import com.example.ui.theme.SeafoamCrest
import com.example.ui.theme.SunlitSand

@Composable
fun DevicePickerDialog(
    isScanning: Boolean,
    devices: List<DiscoveredDevice>,
    onDeviceSelected: (DiscoveredDevice) -> Unit,
    onStartScan: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .testTag("device_picker_dialog")
            .border(1.dp, SeaGlassBorder, RoundedCornerShape(22.dp)),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Bluetooth,
                        contentDescription = "Bluetooth",
                        tint = SeafoamCrest,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Pick SeaMesh Unit",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = SunlitSand
                    )
                }

                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = CoralBuoy
                    )
                } else {
                    IconButton(
                        onClick = onStartScan,
                        modifier = Modifier.testTag("rescan_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Rescan",
                            tint = SeafoamCrest
                        )
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Scanning for nearby boat ESP32 devices whose name starts with \"SeaMesh-\"...",
                    style = MaterialTheme.typography.bodySmall,
                    color = DriftwoodCream,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                if (devices.isEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = AbyssalKelp.copy(alpha = 0.65f)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, SeaGlassBorder.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                            .padding(vertical = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isScanning)
                                    "Listening for SeaMesh-* transponders..."
                                else
                                    "No SeaMesh units detected yet.\nEnsure your boat ESP32 unit is powered on.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = SeaMistMuted,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(devices, key = { it.address }) { device ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SeaGlassElevated),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, SeaGlassBorder, RoundedCornerShape(12.dp))
                                    .testTag("device_item_${device.address}")
                                    .clickable { onDeviceSelected(device) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = device.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = SunlitSand
                                        )
                                        Text(
                                            text = device.address,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = NauticalMonoFamily,
                                            color = DriftwoodCream
                                        )
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(start = 8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.SignalCellularAlt,
                                            contentDescription = "Signal",
                                            tint = SeafoamCrest,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "${device.rssi} dBm",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = NauticalMonoFamily,
                                            color = SeafoamCrest,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dismiss_picker_button")
            ) {
                Text("Cancel", color = CoralBuoy, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = SeaGlassSurface,
        shape = RoundedCornerShape(22.dp)
    )
}
