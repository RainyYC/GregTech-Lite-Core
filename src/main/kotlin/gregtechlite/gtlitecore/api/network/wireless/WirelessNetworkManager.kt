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
 * Energy transfer occurs every 100 ticks (5 seconds at 20 tps).
 */
object WirelessNetworkManager {

    // Channel -> List of wireless energy holders in that channel
    private val networks: MutableMap<Int, MutableList<WirelessEnergyHolder>> = ConcurrentHashMap()

    // Track the last transfer tick to implement 5-tick interval
    private var lastTransferTick: Long = 0

    fun register(holder: WirelessEnergyHolder) {
        networks.computeIfAbsent(holder.channel) { mutableListOf() }.add(holder)
        GTLiteLog.logger.info("Wireless holder registered: channel=${holder.channel}, pos=${holder.pos}, isInput=${holder.isInput}")
    }

    fun unregister(holder: WirelessEnergyHolder) {
        networks[holder.channel]?.remove(holder)
        if (networks[holder.channel]?.isEmpty() == true) {
            networks.remove(holder.channel)
        }
        GTLiteLog.logger.info("Wireless holder unregistered: channel=${holder.channel}, pos=${holder.pos}")
    }

    fun getHolders(channel: Int): List<WirelessEnergyHolder> {
        return networks[channel]?.toList() ?: emptyList()
    }

    fun getChannels(): Set<Int> = networks.keys.toSet()

    fun getConnectionCount(channel: Int): Int = networks[channel]?.size ?: 0

    fun getInputHolders(channel: Int): List<WirelessEnergyHolder> {
        return networks[channel]?.filter { it.role == WirelessRole.INPUT } ?: emptyList()
    }

    fun getOutputHolders(channel: Int): List<WirelessEnergyHolder> {
        return networks[channel]?.filter { it.role == WirelessRole.OUTPUT } ?: emptyList()
    }

    fun getStorageHolders(channel: Int): List<WirelessEnergyHolder> {
        return networks[channel]?.filter { it.role == WirelessRole.STORAGE } ?: emptyList()
    }

    fun update(world: World) {
        if (world.isRemote) return
        if (world.totalWorldTime - lastTransferTick < 100) return
        lastTransferTick = world.totalWorldTime
        networks.forEach { (channel, holders) -> transferChannel(channel, holders) }
    }

    private fun transferChannel(channel: Int, holders: List<WirelessEnergyHolder>) {
        if (holders.isEmpty()) return

        val outputs = holders.filter { it.role == WirelessRole.OUTPUT }
        val inputs = holders.filter { it.role == WirelessRole.INPUT }
        val storages = holders.filter { it.role == WirelessRole.STORAGE }

        if (outputs.isEmpty() && storages.isEmpty()) return

        var remaining = 0L

        // Step 1: OUTPUT -> INPUT (if OUTPUT and INPUT exist)
        if (outputs.isNotEmpty() && inputs.isNotEmpty()) {
            remaining = outputs.sumOf { it.buffer }
            remaining = distributeToInputs(outputs, inputs, remaining)
        }

        // Step 2: STORAGE -> INPUT (always, if both exist and INPUT needs energy)
        if (inputs.isNotEmpty() && storages.isNotEmpty()) {
            fillInputsFromStorage(inputs, storages)
        }

        // Step 3: OUTPUT -> STORAGE (if OUTPUT exists and has remaining energy)
        if (outputs.isNotEmpty() && storages.isNotEmpty() && remaining > 0) {
            storeToStorages(storages, remaining)
        }
    }

    /**
     * Distribute energy from outputs to inputs.
     * Returns remaining energy in outputs after distribution.
     */
    private fun distributeToInputs(
        outputs: List<WirelessEnergyHolder>,
        inputs: List<WirelessEnergyHolder>,
        initialRemaining: Long
    ): Long {
        var remaining = initialRemaining
        if (remaining <= 0 || inputs.isEmpty()) return remaining

        // First pass: equal distribution
        for (input in inputs) {
            if (remaining <= 0) break
            val canAccept = input.capacity - input.buffer
            if (canAccept <= 0) continue

            val share = remaining / inputs.size.coerceAtLeast(1)
            val transferred = transferOnce(outputs, input, minOf(share, canAccept))
            remaining -= transferred
        }

        // Second pass: remaining to those who can still accept
        if (remaining > 0) {
            for (input in inputs) {
                if (remaining <= 0) break
                val canAccept = input.capacity - input.buffer
                if (canAccept <= 0) continue

                val transferred = transferOnce(outputs, input, minOf(remaining, canAccept))
                remaining -= transferred
            }
        }
        return remaining
    }

    /**
     * Fill input hatches from storage if they still need energy.
     */
    private fun fillInputsFromStorage(
        inputs: List<WirelessEnergyHolder>,
        storages: List<WirelessEnergyHolder>
    ) {
        for (input in inputs) {
            var canAccept = input.capacity - input.buffer
            if (canAccept <= 0) continue

            for (storage in storages) {
                if (canAccept <= 0) break
                val available = storage.buffer
                if (available <= 0) continue

                val toTake = minOf(available, canAccept)
                storage.removeEnergy(toTake)
                input.addEnergy(toTake)
                canAccept -= toTake
            }
        }
    }

    /**
     * Store remaining output energy to storage.
     * Note: The 'remaining' energy was already removed from outputs in Step 1 (distributeToInputs),
     * so we just need to add it to storage without removing from outputs again.
     */
    private fun storeToStorages(
        storages: List<WirelessEnergyHolder>,
        remaining: Long
    ) {
        if (storages.isEmpty() || remaining <= 0) return
        var leftover = remaining
        for (storage in storages) {
            if (leftover <= 0) break
            val canAccept = storage.capacity - storage.buffer
            if (canAccept <= 0) continue

            val toTransfer = minOf(leftover, canAccept)
            storage.addEnergy(toTransfer)
            leftover -= toTransfer
        }
    }

    /**
     * Transfer energy from outputs to a single input.
     * Returns actual amount transferred.
     */
    private fun transferOnce(
        outputs: List<WirelessEnergyHolder>,
        input: WirelessEnergyHolder,
        amount: Long
    ): Long {
        if (amount <= 0) return 0L
        val removed = removeFromOutputs(outputs, amount)
        if (removed > 0) {
            input.addEnergy(removed)
        }
        return removed
    }

    /**
     * Remove energy from outputs evenly. Returns actual amount removed.
     */
    private fun removeFromOutputs(outputs: List<WirelessEnergyHolder>, amount: Long): Long {
        if (outputs.isEmpty() || amount <= 0) return 0L
        val totalAvailable = outputs.sumOf { it.buffer }
        if (totalAvailable <= 0) return 0L

        val actual = minOf(amount, totalAvailable)
        val perHolder = actual / outputs.size
        val extra = (actual % outputs.size).toInt()

        outputs.forEachIndexed { idx, output ->
            val toRemove = perHolder + if (idx < extra) 1 else 0
            output.removeEnergy(toRemove)
        }
        return actual
    }

    fun clear() {
        networks.clear()
        GTLiteLog.logger.info("WirelessNetworkManager cleared")
    }
}