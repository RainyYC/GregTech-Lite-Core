package gregtechlite.gtlitecore.integration.theoneprobe.provider

import gregtech.api.metatileentity.interfaces.IGregTechTileEntity
import gregtechlite.gtlitecore.api.MOD_ID
import gregtechlite.gtlitecore.api.network.wireless.WirelessNetworkManager
import gregtechlite.gtlitecore.common.metatileentity.part.WirelessDynamoHatch
import gregtechlite.gtlitecore.common.metatileentity.part.WirelessEnergyHatch
import gregtechlite.gtlitecore.common.metatileentity.part.WirelessStorageHatch
import mcjty.theoneprobe.api.IProbeHitData
import mcjty.theoneprobe.api.IProbeInfo
import mcjty.theoneprobe.api.IProbeInfoProvider
import mcjty.theoneprobe.api.NumberFormat
import mcjty.theoneprobe.api.ProbeMode
import mcjty.theoneprobe.apiimpl.elements.ElementProgress
import net.minecraft.block.state.IBlockState
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.world.World

/**
 * TOP provider for Wireless Energy Hatches, Wireless Dynamo Hatches, and Wireless Storage Hatches.
 * Displays channel info, buffer status, and connection count.
 */
class WirelessHatchInfoProvider : IProbeInfoProvider {

    override fun addProbeInfo(mode: ProbeMode, info: IProbeInfo, player: EntityPlayer,
                              worldIn: World, state: IBlockState, data: IProbeHitData) {
        if (!state.block.hasTileEntity(state)) return

        val tile = worldIn.getTileEntity(data.pos) ?: return
        if (tile !is IGregTechTileEntity) return

        val mte = tile.metaTileEntity ?: return

        // Determine hatch type
        val isInput = mte is WirelessEnergyHatch
        val isOutput = mte is WirelessDynamoHatch
        val isStorage = mte is WirelessStorageHatch

        if (!isInput && !isOutput && !isStorage) return

        // Get channel and buffer info based on hatch type
        val channel: Int
        val bufferStored: Long
        val bufferCapacity: Long

        when (mte) {
            is WirelessEnergyHatch -> {
                channel = mte.getChannel()
                bufferStored = mte.getBufferEnergyStored()
                bufferCapacity = mte.getBufferCapacity()
            }
            is WirelessDynamoHatch -> {
                channel = mte.getChannel()
                bufferStored = mte.getBufferEnergyStored()
                bufferCapacity = mte.getBufferCapacity()
            }
            is WirelessStorageHatch -> {
                channel = mte.getChannel()
                bufferStored = mte.getBufferEnergyStored()
                bufferCapacity = mte.getBufferCapacity()
            }
            else -> return
        }

        // Display channel info
        if (channel > 0) {
            val roleText = when {
                isInput -> "§a输入"
                isOutput -> "§c输出"
                isStorage -> "§e存储"
                else -> "??"
            }
            info.text("$roleText 频道: §e$channel")
            val connectionCount = WirelessNetworkManager.getConnectionCount(channel)
            info.text("连接数: §b$connectionCount")
        } else {
            info.text("§c未连接频道")
        }

        // Display buffer progress
        if (bufferCapacity > 0) {
            val suffix = if (bufferCapacity >= 10000) "EU" else " EU"
            val format = if (bufferStored >= 10000 || bufferCapacity >= 10000)
                NumberFormat.COMPACT else NumberFormat.COMMAS

            val filledColor = when {
                isInput -> 0xFF00AA00.toInt()
                isOutput -> 0xFFAA0000.toInt()
                isStorage -> 0xFFAA8800.toInt()
                else -> 0xFF888888.toInt()
            }
            val altColor = when {
                isInput -> 0xFF00FF00.toInt()
                isOutput -> 0xFFFF0000.toInt()
                isStorage -> 0xFFFFAA00.toInt()
                else -> 0xFF888888.toInt()
            }

            info.progress(bufferStored, bufferCapacity, info.defaultProgressStyle()
                    .numberFormat(format)
                    .suffix(" / " + ElementProgress.format(bufferCapacity, format, suffix))
                    .filledColor(filledColor)
                    .alternateFilledColor(altColor)
                    .borderColor(0xFF555555.toInt()))
        }
    }

    override fun getID(): String = "${MOD_ID}:wireless_hatch_provider"
}
