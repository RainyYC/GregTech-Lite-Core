package gregtechlite.gtlitecore.api.network.wireless

import gregtech.api.capability.IEnergyContainer
import net.minecraft.util.math.BlockPos
import java.util.Objects

/**
 * Represents a wireless energy connection holder.
 * Each wireless hatch (energy or dynamo) has one of these in the wireless network.
 */
class WirelessEnergyHolder(
    val channel: Int,
    val buffer: Long,
    val capacity: Long,
    val isInput: Boolean,
    val energyContainer: IEnergyContainer,
    val pos: BlockPos
) {

    fun getBufferEnergyStored(): Long = buffer

    fun getBufferCapacity(): Long = capacity

    fun canAddEnergy(amount: Long): Boolean = buffer + amount <= capacity

    fun addEnergy(amount: Long): Long {
        val actualAdded = minOf(amount, capacity - buffer)
        return actualAdded
    }

    fun removeEnergy(amount: Long): Long {
        val actualRemoved = minOf(amount, buffer)
        return actualRemoved
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WirelessEnergyHolder) return false
        return channel == other.channel && pos == other.pos
    }

    override fun hashCode(): Int = Objects.hash(channel, pos)
}
