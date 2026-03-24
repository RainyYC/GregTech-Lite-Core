package gregtechlite.gtlitecore.common.metatileentity.part

import codechicken.lib.render.CCRenderState
import codechicken.lib.render.pipeline.IVertexOperation
import codechicken.lib.vec.Matrix4
import gregtech.api.GTValues
import gregtech.api.capability.GregtechDataCodes
import gregtech.api.capability.IEnergyContainer
import gregtech.api.capability.impl.EnergyContainerHandler
import gregtech.api.metatileentity.MetaTileEntity
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity
import gregtech.api.metatileentity.multiblock.AbilityInstances
import gregtech.api.metatileentity.multiblock.IMultiblockAbilityPart
import gregtech.api.metatileentity.multiblock.MultiblockAbility
import gregtech.client.renderer.texture.Textures
import gregtech.common.metatileentities.multi.multiblockpart.MetaTileEntityMultiblockPart
import gregtechlite.gtlitecore.api.network.wireless.WirelessEnergyHolder
import gregtechlite.gtlitecore.api.network.wireless.WirelessNetworkManager
import net.minecraft.client.resources.I18n
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.network.PacketBuffer
import net.minecraft.util.ResourceLocation
import net.minecraft.world.World

/**
 * Wireless Energy Hatch - receives energy from wireless network (input hatch)
 */
class WirelessEnergyHatch(
    id: ResourceLocation,
    tier: Int,
    private val amperage: Int = 1
) : MetaTileEntityMultiblockPart(id, tier), IMultiblockAbilityPart<IEnergyContainer> {

    private var channel: Int = 0
    private var energyBuffer: Long = 0L
    private val bufferCapacity: Long = GTValues.V[tier] * 16L * amperage
    private var wirelessHolder: WirelessEnergyHolder? = null

    private lateinit var energyContainer: IEnergyContainer

    init {
        initializeEnergyContainer()
    }

    private fun initializeEnergyContainer() {
        energyContainer = EnergyContainerHandler.receiverContainer(
            this,
            GTValues.V[tier] * 16L * amperage,
            GTValues.V[tier],
            amperage.toLong()
        )
    }

    override fun createMetaTileEntity(tileEntity: IGregTechTileEntity?): MetaTileEntity {
        return WirelessEnergyHatch(metaTileEntityId, getTier(), amperage)
    }

    override fun getAbility(): MultiblockAbility<IEnergyContainer>? {
        return MultiblockAbility.INPUT_ENERGY
    }

    override fun registerAbilities(abilityInstances: AbilityInstances) {
        abilityInstances.add(energyContainer)
    }

    override fun initializeInventory() {
        super.initializeInventory()
        initializeEnergyContainer()
    }

    override fun update() {
        super.update()
        if (world.isRemote) return

        // Update wireless network every 5 ticks
        if (offsetTimer % 5 == 0L) {
            updateWirelessConnection()
        }

        // Transfer energy from buffer to the machine every 20 ticks
        if (offsetTimer % 20 == 0L && energyBuffer > 0) {
            val canAccept = energyContainer.energyCanBeInserted
            if (canAccept > 0) {
                val toTransfer = minOf(energyBuffer, canAccept)
                val transferred = energyContainer.addEnergy(toTransfer)
                energyBuffer -= transferred
            }
        }
    }

    private fun updateWirelessConnection() {
        val currentHolder = wirelessHolder

        // Update channel for existing holder
        if (currentHolder != null) {
            if (currentHolder.channel != channel) {
                // Channel changed, re-register
                WirelessNetworkManager.unregister(currentHolder)
                val newHolder = createWirelessHolder()
                WirelessNetworkManager.register(newHolder)
                wirelessHolder = newHolder
            }
        } else if (channel != 0) {
            // New registration needed
            val newHolder = createWirelessHolder()
            WirelessNetworkManager.register(newHolder)
            wirelessHolder = newHolder
        }
    }

    private fun createWirelessHolder(): WirelessEnergyHolder {
        return WirelessEnergyHolder(
            channel = channel,
            buffer = energyBuffer,
            capacity = bufferCapacity,
            isInput = true,
            energyContainer = energyContainer,
            pos = pos
        )
    }

    fun getChannel(): Int = channel

    fun setChannel(newChannel: Int) {
        if (channel != newChannel) {
            channel = newChannel
            if (!world.isRemote) {
                markDirty()
                writeCustomData(GregtechDataCodes.UPDATE_WIRELESS_CHANNEL) { buf -> buf.writeInt(channel) }
            }
        }
    }

    fun getBufferEnergyStored(): Long = energyBuffer

    fun getBufferCapacity(): Long = bufferCapacity

    fun getConnectionCount(): Int {
        return if (channel > 0) WirelessNetworkManager.getConnectionCount(channel) else 0
    }

    override fun renderMetaTileEntity(renderState: CCRenderState?, translation: Matrix4?, pipeline: Array<IVertexOperation?>?) {
        super.renderMetaTileEntity(renderState, translation, pipeline)
        if (shouldRenderOverlay()) {
            Textures.ENERGY_IN.renderSided(getFrontFacing(), renderState, translation, pipeline)
        }
    }

    override fun openGUIOnRightClick(): Boolean = false

    override fun writeInitialSyncData(buf: PacketBuffer) {
        super.writeInitialSyncData(buf)
        buf.writeInt(channel)
    }

    override fun receiveInitialSyncData(buf: PacketBuffer) {
        super.receiveInitialSyncData(buf)
        channel = buf.readInt()
    }

    override fun receiveCustomData(dataId: Int, buf: PacketBuffer) {
        super.receiveCustomData(dataId, buf)
        if (dataId == GregtechDataCodes.UPDATE_WIRELESS_CHANNEL) {
            channel = buf.readInt()
        }
    }

    override fun writeToNBT(data: NBTTagCompound): NBTTagCompound {
        super.writeToNBT(data)
        data.setInteger("wireless_channel", channel)
        data.setLong("wireless_buffer", energyBuffer)
        return data
    }

    override fun readFromNBT(data: NBTTagCompound) {
        super.readFromNBT(data)
        channel = data.getInteger("wireless_channel")
        energyBuffer = data.getLong("wireless_buffer")
    }

    override fun addInformation(stack: ItemStack?, world: World?, tooltip: MutableList<String>, advanced: Boolean) {
        tooltip.add(I18n.format("gtlitecore.machine.wireless_energy_hatch.tooltip"))
        tooltip.add(I18n.format("gregtech.universal.tooltip.voltage_in", GTValues.V[getTier()], GTValues.VNF[getTier()]))
        tooltip.add(I18n.format("gregtech.universal.tooltip.amperage_in_till", amperage))
        tooltip.add(I18n.format("gregtech.universal.tooltip.energy_storage_capacity", bufferCapacity))
        tooltip.add(I18n.format("gtlitecore.machine.wireless_energy_hatch.channel", channel))
    }

    override fun onRemoval() {
        wirelessHolder?.let { WirelessNetworkManager.unregister(it) }
        super.onRemoval()
    }
}
