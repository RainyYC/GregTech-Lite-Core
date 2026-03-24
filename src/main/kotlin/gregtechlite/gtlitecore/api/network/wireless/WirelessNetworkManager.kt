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
        return networks[channel]?.filter { it.isInput } ?: emptyList()
    }

    /**
     * Get all holders that are output hatches (dynamo/senders) in a channel.
     */
    fun getOutputHolders(channel: Int): List<WirelessEnergyHolder> {
        return networks[channel]?.filter { !it.isInput } ?: emptyList()
    }

    /**
     * Main update function called every tick.
     * Performs energy transfer every 5 ticks.
     */
    fun update(world: World) {
        if (world.isRemote) return

        val currentTick = world.totalWorldTime
        if (currentTick - lastTransferTick < 5) return

        lastTransferTick = currentTick
        transferEnergy(world)
    }

    /**
     * Transfer energy within each channel.
     * Output hatches send all their buffered energy to all input hatches.
     */
    private fun transferEnergy(world: World) {
        for ((channel, holders) in networks) {
            if (holders.isEmpty()) continue

            // Collect all available energy from output hatches (dynamo)
            val outputHolders = holders.filter { !it.isInput }
            var totalAvailableEnergy = 0L

            for (output in outputHolders) {
                val outputEnergy = output.energyContainer.energyStored
                if (outputEnergy > 0) {
                    // Draw energy from the output hatch's energy container
                    val drawn = output.energyContainer.removeEnergy(outputEnergy)
                    totalAvailableEnergy += drawn
                }
            }

            if (totalAvailableEnergy <= 0) continue

            // Distribute energy to all input hatches (energy)
            val inputHolders = holders.filter { it.isInput }
            if (inputHolders.isEmpty()) continue

            // Calculate how much each input hatch can receive
            var remainingEnergy = totalAvailableEnergy
            val inputHoldersWithCapacity = inputHolders.map { holder ->
                val canAccept = holder.capacity - holder.buffer
                Triple(holder, canAccept, 0L)
            }.toMutableList()

            // First pass: distribute to those that can accept
            for (i in inputHoldersWithCapacity.indices) {
                val (holder, canAccept, _) = inputHoldersWithCapacity[i]
                if (canAccept <= 0) continue

                val share = remainingEnergy / inputHolders.size
                val actualShare = minOf(share, canAccept)
                inputHoldersWithCapacity[i] = Triple(holder, canAccept, actualShare)
                remainingEnergy -= actualShare
            }

            // Second pass: give remaining energy to those who can still accept
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

            // Actually add energy to input hatches
            for ((holder, _, amount) in inputHoldersWithCapacity) {
                if (amount > 0) {
                    // Add energy to the holder's buffer
                    holder.energyContainer.addEnergy(amount)
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
