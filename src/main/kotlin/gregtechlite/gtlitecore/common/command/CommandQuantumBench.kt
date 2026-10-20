package gregtechlite.gtlitecore.common.command

import gregtech.api.metatileentity.interfaces.IGregTechTileEntity
import gregtechlite.gtlitecore.common.metatileentity.multiblock.storage.MultiblockQuantumChest
import net.minecraft.command.CommandBase
import net.minecraft.command.ICommandSender
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.init.Items
import net.minecraft.item.ItemStack
import net.minecraft.server.MinecraftServer
import net.minecraft.util.math.RayTraceResult
import net.minecraft.util.text.TextComponentString
import java.math.BigInteger

/**
 * Dev-only command for benchmarking: fills the quantum chest the player is looking at with generated
 * distinct item types. Registered only when the GTLITE_BENCH environment variable or the
 * gtlitecore.bench system property is set. This variant is written against the pre-rework storage
 * (linear lookups, contents kept in the synced field), so the old behaviour can be measured in game.
 */
class CommandQuantumBench : CommandBase()
{
    override fun getName(): String = "quantumbench"

    override fun getUsage(sender: ICommandSender): String = "/gtlitecore quantumbench <fill|clear|status> [types]"

    override fun getRequiredPermissionLevel(): Int = 2

    override fun execute(server: MinecraftServer, sender: ICommandSender, args: Array<out String?>)
    {
        val player = sender as? EntityPlayer ?: return
        val chest = lookedAtChest(player) ?: run {
            sender.sendMessage(TextComponentString("Look at a formed quantum chest first"))
            return
        }

        val storage = chest.itemStorage()
        when (args.getOrNull(0))
        {
            "fill" ->
            {
                val types = args.getOrNull(1)?.toIntOrNull() ?: 10_000
                val started = System.nanoTime()
                var inserted = 0
                for (i in 0 until types)
                {
                    val stack = ItemStack(Items.PAPER)
                    stack.setStackDisplayName("bench_$i")
                    if (storage.insert(stack, BigInteger.valueOf(1000)).signum() > 0) inserted++
                }
                chest.markDirty()
                val millis = (System.nanoTime() - started) / 1_000_000.0
                sender.sendMessage(TextComponentString(
                    "Filled $inserted types in ${String.format("%.1f", millis)} ms, distinct=${storage.distinctSlots()}"))
            }
            "clear" ->
            {
                val started = System.nanoTime()
                // The old entries() is a live view, so snapshot before extracting.
                val snapshot = storage.entries().map { it.key to it.value }
                for ((type, amount) in snapshot) storage.extract(type, amount)
                chest.markDirty()
                val millis = (System.nanoTime() - started) / 1_000_000.0
                sender.sendMessage(TextComponentString(
                    "Cleared ${snapshot.size} types in ${String.format("%.1f", millis)} ms, distinct=${storage.distinctSlots()}"))
            }
            "status" -> sender.sendMessage(TextComponentString(
                "distinct=${storage.distinctSlots()} max=${storage.maxDistinct} stored=${storage.totalStored()} capacity=${storage.totalCapacity}"))
            else -> sender.sendMessage(TextComponentString(getUsage(sender)))
        }
    }

    private fun lookedAtChest(player: EntityPlayer): MultiblockQuantumChest?
    {
        val hit = player.rayTrace(12.0, 1.0f) ?: return null
        if (hit.typeOfHit != RayTraceResult.Type.BLOCK) return null
        val tile = player.world.getTileEntity(hit.blockPos) as? IGregTechTileEntity ?: return null
        return tile.metaTileEntity as? MultiblockQuantumChest
    }
}
