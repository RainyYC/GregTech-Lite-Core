package gregtechlite.gtlitecore.common.wireless

import gregtechlite.gtlitecore.api.MOD_ID
import gregtechlite.gtlitecore.api.network.wireless.WirelessNetworkManager
import net.minecraft.world.World
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent.WorldTickEvent

/**
 * Handles server tick events for wireless energy networks.
 * Updates all wireless channels periodically to perform energy transfer.
 */
@EventBusSubscriber(modid = MOD_ID)
object WirelessTickHandler {

    @SubscribeEvent
    @JvmStatic
    fun onWorldTick(event: WorldTickEvent) {
        val world = event.world ?: return
        if (world.isRemote) return

        // Delegate to WirelessNetworkManager for tick processing
        WirelessNetworkManager.update(world)
    }
}
