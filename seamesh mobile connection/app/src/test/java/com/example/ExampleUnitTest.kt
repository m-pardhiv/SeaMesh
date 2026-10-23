package com.example

import com.example.data.PacketQueueManager
import com.example.model.SeaMeshPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testPacketParsingValidNotification() {
        val raw = "S,3F2A,7,9.951234,75.948765,42,0,BTN,3F2A,91C0,-61"
        val packet = SeaMeshPacket.parse(raw)

        assertNotNull(packet)
        assertEquals("S", packet?.type)
        assertEquals("3F2A", packet?.boat)
        assertEquals(7L, packet?.msgId)
        assertEquals(9.951234, packet!!.lat, 0.000001)
        assertEquals(75.948765, packet.lon, 0.000001)
        assertEquals(42L, packet.time)
        assertEquals(0, packet.hops)
        assertEquals("BTN", packet.trigger)
        assertEquals("3F2A", packet.via)
        assertEquals("91C0", packet.gateway)
        assertEquals(-61, packet.rssi)
        assertTrue(packet.isSos)
    }

    @Test
    fun testPacketParsingInvalidNotificationIgnored() {
        // Less than 11 fields
        val tooFew = "S,3F2A,7,9.951234,75.948765,42,0,BTN,3F2A,91C0"
        assertNull(SeaMeshPacket.parse(tooFew))

        // More than 11 fields
        val tooMany = "S,3F2A,7,9.951234,75.948765,42,0,BTN,3F2A,91C0,-61,EXTRA"
        assertNull(SeaMeshPacket.parse(tooMany))

        // Non-number in number field
        val badNumber = "S,3F2A,not_a_number,9.951234,75.948765,42,0,BTN,3F2A,91C0,-61"
        assertNull(SeaMeshPacket.parse(badNumber))
    }

    @Test
    fun testPacketJsonSchema() {
        val raw = "S,3F2A,7,9.951234,75.948765,42,0,BTN,3F2A,91C0,-61"
        val packet = SeaMeshPacket.parse(raw)!!
        val json = packet.toJson()

        assertTrue(json.contains("\"type\":\"S\""))
        assertTrue(json.contains("\"boat\":\"3F2A\""))
        assertTrue(json.contains("\"msgId\":7"))
        assertTrue(json.contains("\"lat\":9.951234"))
        assertTrue(json.contains("\"lon\":75.948765"))
        assertTrue(json.contains("\"time\":42"))
        assertTrue(json.contains("\"hops\":0"))
        assertTrue(json.contains("\"trigger\":\"BTN\""))
        assertTrue(json.contains("\"via\":\"3F2A\""))
        assertTrue(json.contains("\"gateway\":\"91C0\""))
        assertTrue(json.contains("\"rssi\":-61"))
        assertTrue(json.contains("\"receivedAt\":"))
    }

    @Test
    fun testPacketQueuePrioritizationAndCapacity() {
        val queue = PacketQueueManager(maxCapacity = 3)

        val pos1 = SeaMeshPacket("P", "B1", 1, 9.0, 75.0, 10, 0, "AUTO", "B1", "GW", -70)
        val pos2 = SeaMeshPacket("P", "B2", 2, 9.1, 75.1, 11, 0, "AUTO", "B2", "GW", -70)
        val pos3 = SeaMeshPacket("P", "B3", 3, 9.2, 75.2, 12, 0, "AUTO", "B3", "GW", -70)
        val sos1 = SeaMeshPacket("S", "B4", 4, 9.3, 75.3, 13, 0, "BTN", "B4", "GW", -70)

        assertTrue(queue.enqueue(pos1))
        assertTrue(queue.enqueue(pos2))
        assertTrue(queue.enqueue(pos3))
        assertEquals(3, queue.size())

        // Adding SOS packet when full: drops oldest position packet (pos1), and puts SOS first!
        assertTrue(queue.enqueue(sos1))
        assertEquals(3, queue.size())

        val batch = queue.getNextBatch()
        assertEquals(3, batch.size)
        // SOS packet should be at the front
        assertEquals("B4", batch[0].boat)
        assertTrue(batch[0].isSos)

        // pos1 was dropped, pos2 and pos3 remain
        assertFalse(batch.any { it.boat == "B1" })
        assertTrue(batch.any { it.boat == "B2" })
        assertTrue(batch.any { it.boat == "B3" })
    }

    @Test
    fun testPacketDeduplicationAfterUploaded() {
        val queue = PacketQueueManager(maxCapacity = 10)
        val packet = SeaMeshPacket("S", "3F2A", 7, 9.95, 75.94, 42, 0, "BTN", "3F2A", "GW", -65)

        assertTrue(queue.enqueue(packet))
        assertEquals(1, queue.size())

        // Mark uploaded
        queue.markUploaded(packet)
        assertEquals(0, queue.size())
        assertTrue(queue.isAlreadyUploaded(packet))

        // Subsequent enqueue should be rejected
        assertFalse(queue.enqueue(packet))
        assertEquals(0, queue.size())
    }
}
