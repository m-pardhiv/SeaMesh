package com.example.model

enum class BleConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    NOT_FOUND
}

data class DiscoveredDevice(
    val name: String,
    val address: String,
    val rssi: Int
)

data class SeaMeshPacket(
    val type: String, // "S" (SOS) or "P" (Position)
    val boat: String,
    val msgId: Long,
    val lat: Double,
    val lon: Double,
    val time: Long,
    val hops: Int,
    val trigger: String,
    val via: String,
    val gateway: String,
    val rssi: Int,
    val receivedAt: Long = System.currentTimeMillis()
) {
    val isSos: Boolean get() = type.equals("S", ignoreCase = true)
    val key: String get() = "$boat:$msgId"

    fun toJson(): String {
        return "{\"type\":\"$type\"," +
                "\"boat\":\"$boat\"," +
                "\"msgId\":$msgId," +
                "\"lat\":$lat," +
                "\"lon\":$lon," +
                "\"time\":$time," +
                "\"hops\":$hops," +
                "\"trigger\":\"$trigger\"," +
                "\"via\":\"$via\"," +
                "\"gateway\":\"$gateway\"," +
                "\"rssi\":$rssi," +
                "\"receivedAt\":$receivedAt}"
    }

    companion object {
        /**
         * Parses notification from PACKETS characteristic:
         * "<type>,<origin>,<msgId>,<lat>,<lon>,<time>,<hop>,<trigger>,<via>,<gateway>,<rssi>"
         * e.g. "S,3F2A,7,9.951234,75.948765,42,0,BTN,3F2A,91C0,-61"
         * Ignore any notification that does not have exactly 11 comma-separated fields.
         */
        fun parse(raw: String): SeaMeshPacket? {
            val parts = raw.trim().split(",")
            if (parts.size != 11) return null

            return try {
                val type = parts[0].trim()
                val origin = parts[1].trim()
                val msgId = parts[2].trim().toLong()
                val lat = parts[3].trim().toDouble()
                val lon = parts[4].trim().toDouble()
                val time = parts[5].trim().toLong()
                val hop = parts[6].trim().toInt()
                val trigger = parts[7].trim()
                val via = parts[8].trim()
                val gateway = parts[9].trim()
                val rssi = parts[10].trim().toInt()

                SeaMeshPacket(
                    type = type,
                    boat = origin,
                    msgId = msgId,
                    lat = lat,
                    lon = lon,
                    time = time,
                    hops = hop,
                    trigger = trigger,
                    via = via,
                    gateway = gateway,
                    rssi = rssi
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
