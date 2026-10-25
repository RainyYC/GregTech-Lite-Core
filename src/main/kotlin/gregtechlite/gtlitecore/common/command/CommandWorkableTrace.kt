package gregtechlite.gtlitecore.common.command

import gregtech.api.capability.impl.AbstractRecipeLogic
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity
import gregtech.api.metatileentity.multiblock.MultiblockAbility
import gregtech.api.metatileentity.multiblock.RecipeMapMultiblockController
import gregtechlite.gtlitecore.api.LOGGER
import gregtechlite.gtlitecore.common.metatileentity.part.PartMachineWirelessHatch
import net.minecraft.command.CommandBase
import net.minecraft.command.ICommandSender
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.server.MinecraftServer
import net.minecraft.util.math.RayTraceResult
import net.minecraft.util.text.TextComponentString
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent

/**
 * Dev-only command: logs the recipe logic state of the machine the player is looking at, one line per
 * tick, so a stall can be attributed to the exact gate that is closed. Registered only when
 * GTLITE_BENCH or -Dgtlitecore.bench=true is set.
 */
class CommandWorkableTrace : CommandBase()
{
    override fun getName(): String = "workabletrace"

    override fun getUsage(sender: ICommandSender): String = "/gtlitecore workabletrace [ticks]"

    override fun getRequiredPermissionLevel(): Int = 2

    override fun execute(server: MinecraftServer, sender: ICommandSender, args: Array<out String?>)
    {
        val player = sender as? EntityPlayer ?: return
        val ticks = args.getOrNull(0)?.toIntOrNull() ?: 120

        val hit = player.rayTrace(12.0, 1.0f)
        if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK)
        {
            sender.sendMessage(TextComponentString("Look at a formed machine first"))
            return
        }
        val tile = player.world.getTileEntity(hit.blockPos) as? IGregTechTileEntity
        val controller = tile?.metaTileEntity as? RecipeMapMultiblockController
        if (controller == null)
        {
            sender.sendMessage(TextComponentString("Not a recipe map multiblock"))
            return
        }

        val logic = controller.recipeMapWorkable
        val wireless = controller.getAbilities(MultiblockAbility.INPUT_ENERGY)
            .filterIsInstance<PartMachineWirelessHatch>()
            .firstOrNull()

        sender.sendMessage(TextComponentString("Tracing ${controller.metaFullName} for $ticks ticks"))
        MinecraftForge.EVENT_BUS.register(Tracer(logic, controller, wireless, ticks))
    }

    private class Tracer(private val logic: AbstractRecipeLogic,
                         private val controller: RecipeMapMultiblockController,
                         private val wireless: PartMachineWirelessHatch?,
                         private var remaining: Int) : Any()
    {
        private var tick = 0

        @SubscribeEvent
        fun onServerTick(event: TickEvent.ServerTickEvent)
        {
            if (event.phase != TickEvent.Phase.END) return
            tick++
            remaining--

            LOGGER.info(
                "TRACE t={} progress={}/{} eut={} active={} enoughE={} outFull={} badIn={} canProg={} par={} nIn={}/{} nOut={}/{} stored={}/{} holder={}",
                tick,
                read(logic, "progressTime"), read(logic, "maxProgressTime"),
                read(logic, "recipeEUt"),
                read(logic, "isActive"),
                !(read(logic, "hasNotEnoughEnergy") as? Boolean ?: false),
                read(logic, "isOutputsFull"),
                read(logic, "invalidInputsForRecipes"),
                read(logic, "canRecipeProgress"),
                read(logic, "parallelRecipesPerformed"),
                controller.notifiedItemInputList.size, controller.notifiedFluidInputList.size,
                controller.notifiedItemOutputList.size, controller.notifiedFluidOutputList.size,
                controller.energyContainer.energyStored, controller.energyContainer.energyCapacity,
                wireless?.getBufferEnergyStored() ?: -1L
            )

            if (remaining <= 0) MinecraftForge.EVENT_BUS.unregister(this)
        }

        private fun read(target: Any, name: String): Any?
        {
            var cls: Class<*>? = target.javaClass
            while (cls != null)
            {
                try
                {
                    val field = cls.getDeclaredField(name)
                    field.isAccessible = true
                    return field.get(target)
                }
                catch (_: NoSuchFieldException)
                {
                    cls = cls.superclass
                }
            }
            return null
        }
    }
}
