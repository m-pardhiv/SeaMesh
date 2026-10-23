package com.example.data

import com.example.model.SeaMeshPacket
import java.util.Collections

class PacketQueueManager(private val maxCapacity: Int = 50) {
    private val uploadedKeys = Collections.synchronizedSet(HashSet<String>())
    private val queue = ArrayList<SeaMeshPacket>()
    private val lock = Any()

    fun isAlreadyUploaded(packet: SeaMeshPacket): Boolean {
        return uploadedKeys.contains(packet.key)
    }

    fun markUploaded(packet: SeaMeshPacket) {
        uploadedKeys.add(packet.key)
        synchronized(lock) {
            queue.removeAll { it.key == packet.key }
        }
    }

    fun enqueue(packet: SeaMeshPacket): Boolean {
        if (isAlreadyUploaded(packet)) {
            return false
        }

        synchronized(lock) {
            // Avoid duplicates in queue
            if (queue.any { it.key == packet.key }) {
                return false
            }

            if (queue.size >= maxCapacity) {
                // Drop the oldest position packet if available
                val positionIndex = queue.indexOfFirst { !it.isSos }
                if (positionIndex >= 0) {
                    queue.removeAt(positionIndex)
                } else {
                    // All are SOS, drop the oldest SOS packet
                    queue.removeAt(0)
                }
            }

            queue.add(packet)
            // Prioritize SOS packets first, keeping arrival order within each category
            queue.sortWith(compareByDescending { it.isSos })
            return true
        }
    }

    fun getNextBatch(): List<SeaMeshPacket> {
        synchronized(lock) {
            return ArrayList(queue)
        }
    }

    fun remove(packet: SeaMeshPacket) {
        synchronized(lock) {
            queue.removeAll { it.key == packet.key }
        }
    }

    fun size(): Int {
        synchronized(lock) {
            return queue.size
        }
    }

    fun clear() {
        synchronized(lock) {
            queue.clear()
        }
    }
}
