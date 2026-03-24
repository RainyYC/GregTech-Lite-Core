package gregtechlite.gtlitecore.api.network.wireless

import gregtech.api.capability.IEnergyContainer
import gregtechlite.gtlitecore.api.GTLiteAPI
import gregtechlite.gtlitecore.api.GTLiteLog
import gregtechlite.gtlitecore.api.TICK
import net.minecraft.world.World
import java.util.concurrent.ConcurrentHashMap

/**
 * Manager for all wireless energy networks.
 * Handles registration, unregistration, and energy transfer between channels.
 * Energy transfer occurs every 5 ticks (4 times per second at 20 tps).
 */
object WirelessNetworkManager {

    // Channel -> List of wireless energy holders in that channel
    private val networks: MutableMap<Int, MutableList<WirelessEnergyHolder>> = ConcurrentHashMap()

    // Track the last transfer tick to implement 5-tick interval
    private var lastTransferTick: Long = 0

    /**
     * Register a new wireless energy holder to its channel.
     */
    fun register(holder: WirelessEnergyHolder) {
        networks.computeIfAbsent(holder.channel) { mutableListOf() }.add(holder)
        GTLiteLog.logger.info("Wireless holder registered: channel=${holder.channel}, pos=${holder.pos}, isInput=${holder.isInput}")
    }

    /**
     * Unregister a wireless holder from its channel.
     */
    fun unregister(holder: WirelessEnergyHolder) {
        networks[holder.channel]?.remove(holder)
        if (networks[holder.channel]?.isEmpty() == true) {
            networks.remove(holder.channel)
        }
        GTLiteLog.logger.info("Wireless holder unregistered: channel=${holder.channel}, pos=${holder.pos}")
    }

    /**
     * Get all holders in a specific channel.
     */
    fun getHolders(channel: Int): List<WirelessEnergyHolder> {
        return networks[channel]?.toList() ?: emptyList()
    }

    /**
     * Get all active channels.
     */
    fun getChannels(): Set<Int> = networks.keys.toSet()

    /**
     * Get the number of connections for a specific channel.
     */
    fun getConnectionCount(channel: Int): Int = networks[channel]?.size ?: 0

    /**
     * Get all holders that are input hatches (energy receivers) in a channel.
     */
    fun getInputHolders(channel: Int): List<WirelessEnergyHolder> {
        return networks[channel]?.filter { it.role == WirelessRole.INPUT } ?: emptyList()
    }

    /**
     * Get all holders that are output hatches (dynamo/senders) in a channel.
     */
    fun getOutputHolders(channel: Int): List<WirelessEnergyHolder> {
        return networks[channel]?.filter { it.role == WirelessRole.OUTPUT } ?: emptyList()
    }

    /**
     * Get all holders that are storage hatches in a channel.
     */
    fun getStorageHolders(channel: Int): List<WirelessEnergyHolder> {
        return networks[channel]?.filter { it.role == WirelessRole.STORAGE } ?: emptyList()
    }

    /**
     * Main update function called every tick.
     * Performs energy transfer every 5 seconds (100 ticks at 20 tps).
     */
    fun update(world: World) {
        if (world.isRemote) return

        val currentTick = world.totalWorldTime
        if (currentTick - lastTransferTick < 100) return

        lastTransferTick = currentTick
        transferEnergy(world)
    }

    /**
     * Transfer energy within each channel.
     * Energy flow:
     * 1. Collect all energy from OUTPUT holders (dynamo hatches)
     * 2. First distribute to INPUT holders (energy hatches) - they have priority
     * 3. Remaining energy goes to STORAGE holders (if excess)
     * 4. If INPUT holders need more, release from STORAGE to fill the gap
     * 5. Remove distributed energy from OUTPUT holders
     */
    private fun transferEnergy(@Suppress("UNUSED_PARAMETER") world: World) {
        for ((channel, holders) in networks) {
            if (holders.isEmpty()) continue

            // Collect all available energy from output hatches (dynamo)
            val outputHolders = holders.filter { it.role == WirelessRole.OUTPUT }
            val inputHolders = holders.filter { it.role == WirelessRole.INPUT }
            val storageHolders = holders.filter { it.role == WirelessRole.STORAGE }

            var totalAvailableEnergy = 0L

            for (output in outputHolders) {
                if (output.buffer > 0) {
                    totalAvailableEnergy += output.buffer
                }
            }

            // If no output energy and no storage, nothing to do
            if (totalAvailableEnergy <= 0 && storageHolders.all { it.buffer <= 0 }) continue

            // If no input hatches, nothing to distribute to
            if (inputHolders.isEmpty()) continue

            var remainingEnergy = totalAvailableEnergy

            // Step 1: Calculate total input capacity needed
            val inputHoldersWithCapacity = inputHolders.map { holder ->
                val canAccept = holder.capacity - holder.buffer
                Triple(holder, canAccept, 0L)
            }.toMutableList()

            val totalInputCapacityNeeded = inputHoldersWithCapacity.sumOf { it.second }

            // Step 2: First pass - distribute available energy to input hatches
            for (i in inputHoldersWithCapacity.indices) {
                val (holder, canAccept, _) = inputHoldersWithCapacity[i]
                if (canAccept <= 0 || remainingEnergy <= 0) continue

                val share = remainingEnergy / inputHolders.size.coerceAtLeast(1)
                val actualShare = minOf(share, canAccept)
                inputHoldersWithCapacity[i] = Triple(holder, canAccept, actualShare)
                remainingEnergy -= actualShare
            }

            // Step 3: Second pass - give remaining energy to those who can still accept
            if (remainingEnergy > 0) {
                for (i in inputHoldersWithCapacity.indices) {
                    val (holder, canAccept, alreadyGiven) = inputHoldersWithCapacity[i]
                    if (canAccept <= alreadyGiven) continue

                    val canStillAccept = canAccept - alreadyGiven
                    val extraShare = minOf(remainingEnergy, canStillAccept)
                    inputHoldersWithCapacity[i] = Triple(holder, canAccept, alreadyGiven + extraShare)
                    remainingEnergy -= extraShare
                }
            }

            // Step 4: If input hatches still need more, try to get from storage
            var energyFromStorage = 0L
            for (i in inputHoldersWithCapacity.indices) {
                val (_, canAccept, alreadyGiven) = inputHoldersWithCapacity[i]
                val stillNeeded = canAccept - alreadyGiven
                if (stillNeeded > 0) {
                    // Try to get from storage
                    for (storage in storageHolders) {
                        if (storage.buffer > 0 && stillNeeded > energyFromStorage) {
                            val canTake = minOf(storage.buffer, stillNeeded - energyFromStorage)
                            energyFromStorage += canTake
                        }
                    }
                }
            }

            // Step 5: Add energy from storage to input hatches if needed
            if (energyFromStorage > 0) {
                var energyToDistribute = energyFromStorage
                for (i in inputHoldersWithCapacity.indices) {
                    val (holder, canAccept, alreadyGiven) = inputHoldersWithCapacity[i]
                    val stillNeeded = canAccept - alreadyGiven
                    if (stillNeeded > 0 && energyToDistribute > 0) {
                        val toAdd = minOf(stillNeeded, energyToDistribute)
                        inputHoldersWithCapacity[i] = Triple(holder, canAccept, alreadyGiven + toAdd)
                        energyToDistribute -= toAdd
                    }
                }
                // Remove energy taken from storage
                var remainingFromStorage = energyFromStorage
                for (storage in storageHolders) {
                    if (remainingFromStorage <= 0) break
                    val toRemove = minOf(storage.buffer, remainingFromStorage)
                    storage.removeEnergy(toRemove)
                    remainingFromStorage -= toRemove
                }
            }

            // Step 6: Actually add energy to input hatches
            for ((holder, _, amount) in inputHoldersWithCapacity) {
                if (amount > 0) {
                    holder.addEnergy(amount)
                }
            }

            // Step 7: Put remaining energy into storage (if any)
            if (remainingEnergy > 0) {
                for (storage in storageHolders) {
                    if (remainingEnergy <= 0) break
                    val canAccept = storage.capacity - storage.buffer
                    if (canAccept > 0) {
                        val toAdd = minOf(remainingEnergy, canAccept)
                        storage.addEnergy(toAdd)
                        remainingEnergy -= toAdd
                    }
                }
            }

            // Step 8: Remove distributed energy from output hatches
            if (outputHolders.isNotEmpty()) {
                val energyToRemove = totalAvailableEnergy - remainingEnergy
                val removePerHolder = energyToRemove / outputHolders.size
                val extraRemainder = (energyToRemove % outputHolders.size).toInt()

                for ((index, output) in outputHolders.withIndex()) {
                    var toRemove = removePerHolder
                    if (index < extraRemainder) {
                        toRemove += 1
                    }
                    output.buffer = maxOf(0L, output.buffer - toRemove)
                }
            }
        }
    }

    /**
     * Clear all networks. Called during mod shutdown.
     */
    fun clear() {
        networks.clear()
        GTLiteLog.logger.info("WirelessNetworkManager cleared")
    }
}
