package gregtechlite.gtlitecore.common.metatileentity.part

import codechicken.lib.render.CCRenderState
import codechicken.lib.render.pipeline.IVertexOperation
import codechicken.lib.vec.Matrix4
import com.cleanroommc.modularui.api.drawable.IKey
import com.cleanroommc.modularui.factory.PosGuiData
import com.cleanroommc.modularui.screen.ModularPanel
import com.cleanroommc.modularui.screen.UISettings
import com.cleanroommc.modularui.value.sync.IntSyncValue
import com.cleanroommc.modularui.value.sync.PanelSyncManager
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget
import gregtech.api.GTValues
import gregtech.api.capability.IEnergyContainer
import gregtech.api.capability.impl.EnergyContainerHandler
import gregtech.api.metatileentity.MetaTileEntity
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity
import gregtech.api.metatileentity.multiblock.AbilityInstances
import gregtech.api.metatileentity.multiblock.IMultiblockAbilityPart
import gregtech.api.metatileentity.multiblock.MultiblockAbility
import gregtech.api.mui.GTGuiTextures
import gregtech.api.mui.GTGuis
import gregtech.client.renderer.texture.Textures
import gregtech.common.metatileentities.multi.multiblockpart.MetaTileEntityMultiblockPart
import gregtechlite.gtlitecore.api.network.wireless.WirelessEnergyHolder
import gregtechlite.gtlitecore.api.network.wireless.WirelessNetworkManager
import gregtechlite.gtlitecore.api.network.wireless.WirelessRole
import net.minecraft.client.resources.I18n
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.network.PacketBuffer
import net.minecraft.util.ResourceLocation
import net.minecraft.world.World

private const val UPDATE_WIRELESS_CHANNEL = 998877

/**
 * Wireless Dynamo Hatch - outputs energy to wireless network (output hatch)
 */
class WirelessDynamoHatch(
    id: ResourceLocation,
    tier: Int,
    private val amperage: Int = 1
) : MetaTileEntityMultiblockPart(id, tier), IMultiblockAbilityPart<IEnergyContainer> {

    companion object {
        const val MAX_CHANNEL = 16
    }

    private var channel: Int = 0
    private var persistedBuffer: Long = 0L
    // 10 seconds of buffering (10s * 20 ticks/s = 200 ticks)
    private val bufferCapacity: Long = GTValues.V[tier] * 200L * amperage
    private var wirelessHolder: WirelessEnergyHolder? = null

    private lateinit var energyContainer: IEnergyContainer

    init {
        initializeEnergyContainer()
    }

    private fun initializeEnergyContainer() {
        energyContainer = EnergyContainerHandler.emitterContainer(
            this,
            GTValues.V[tier] * 64L * amperage,
            GTValues.V[tier],
            amperage.toLong()
        )
    }

    override fun createMetaTileEntity(tileEntity: IGregTechTileEntity?): MetaTileEntity {
        return WirelessDynamoHatch(metaTileEntityId, getTier(), amperage)
    }

    override fun getAbility(): MultiblockAbility<IEnergyContainer>? {
        return MultiblockAbility.OUTPUT_ENERGY
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

        // Transfer energy from machine to holder buffer every 5 ticks
        if (offsetTimer % 5 == 0L) {
            val holder = wirelessHolder ?: return
            val available = energyContainer.energyStored
            if (available > 0) {
                val canStore = bufferCapacity - holder.buffer
                if (canStore > 0) {
                    val toTransfer = minOf(available, canStore)
                    energyContainer.removeEnergy(toTransfer)
                    holder.buffer += toTransfer
                }
            }
        }
    }

    private fun updateWirelessConnection() {
        val currentHolder = wirelessHolder

        if (currentHolder != null) {
            if (currentHolder.channel != channel) {
                WirelessNetworkManager.unregister(currentHolder)
                val newHolder = createWirelessHolder()
                WirelessNetworkManager.register(newHolder)
                wirelessHolder = newHolder
            }
        } else if (channel != 0) {
            val newHolder = createWirelessHolder()
            WirelessNetworkManager.register(newHolder)
            wirelessHolder = newHolder
        }
    }

    private fun createWirelessHolder(): WirelessEnergyHolder {
        return WirelessEnergyHolder(
            channel = channel,
            buffer = persistedBuffer,
            capacity = bufferCapacity,
            role = WirelessRole.OUTPUT,
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
                writeCustomData(UPDATE_WIRELESS_CHANNEL) { buf -> buf.writeInt(channel) }
            }
        }
    }

    fun getBufferEnergyStored(): Long = wirelessHolder?.buffer ?: 0L

    fun getBufferCapacity(): Long = bufferCapacity

    fun getConnectionCount(): Int {
        return if (channel > 0) WirelessNetworkManager.getConnectionCount(channel) else 0
    }

    override fun renderMetaTileEntity(renderState: CCRenderState?, translation: Matrix4?, pipeline: Array<IVertexOperation?>?) {
        super.renderMetaTileEntity(renderState, translation, pipeline)
        if (shouldRenderOverlay()) {
            Textures.ENERGY_OUT.renderSided(getFrontFacing(), renderState, translation, pipeline)
        }
    }

    override fun openGUIOnRightClick(): Boolean = true

    override fun usesMui2(): Boolean = true

    @Suppress("UnstableApiUsage")
    override fun buildUI(guiData: PosGuiData, panelSyncManager: PanelSyncManager, settings: UISettings): ModularPanel {
        val channelSync = IntSyncValue(
            { this.channel },
            { newChannel -> this.channel = newChannel.coerceIn(0, MAX_CHANNEL) }
        )

        return GTGuis.createPanel(this, 180, 60)
            .child(IKey.lang(metaFullName).asWidget().pos(5, 5))
            .child(IKey.lang("gtlitecore.gui.wireless_hatch.channel_label").asWidget().pos(5, 25))
            .child(TextFieldWidget()
                .pos(90, 22)
                .size(80, 18)
                .setNumbers(0, MAX_CHANNEL)
                .value(channelSync)
                .setTextColor(0xFFAAAA99.toInt()))
    }

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
        if (dataId == UPDATE_WIRELESS_CHANNEL) {
            channel = buf.readInt()
        }
    }

    override fun writeToNBT(data: NBTTagCompound): NBTTagCompound {
        super.writeToNBT(data)
        data.setInteger("wireless_channel", channel)
        data.setLong("wireless_buffer", wirelessHolder?.buffer ?: persistedBuffer)
        return data
    }

    override fun readFromNBT(data: NBTTagCompound) {
        super.readFromNBT(data)
        channel = data.getInteger("wireless_channel")
        persistedBuffer = data.getLong("wireless_buffer")
    }

    override fun addInformation(stack: ItemStack?, world: World?, tooltip: MutableList<String>, advanced: Boolean) {
        tooltip.add(I18n.format("gtlitecore.machine.wireless_dynamo_hatch.tooltip"))
        tooltip.add(I18n.format("gregtech.universal.tooltip.voltage_out", GTValues.V[getTier()], GTValues.VNF[getTier()]))
        tooltip.add(I18n.format("gregtech.universal.tooltip.amperage_out_till", amperage))
        tooltip.add(I18n.format("gregtech.universal.tooltip.energy_storage_capacity", bufferCapacity))
        tooltip.add(I18n.format("gtlitecore.machine.wireless_dynamo_hatch.channel", channel))
    }

    override fun onRemoval() {
        wirelessHolder?.let { WirelessNetworkManager.unregister(it) }
        super.onRemoval()
    }
}
