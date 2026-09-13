package gregtechlite.gtlitecore.common.command

import gregtechlite.gtlitecore.loader.recipe.audit.RecipeAuditMaps
import gregtechlite.gtlitecore.loader.recipe.audit.RecipeMapExporter
import java.io.File
import net.minecraft.command.CommandBase
import net.minecraft.command.CommandException
import net.minecraft.command.ICommandSender
import net.minecraft.server.MinecraftServer
import net.minecraft.util.math.BlockPos
import net.minecraft.util.text.TextComponentString

/**
 * `/gtlite recipeaudit <map>`：把配方表的原始状态导出成 JSON（离线 Python 侧做判定）。
 *
 * 本类**只做转发**：解析表名 → 调 [RecipeMapExporter.export]，自己没有任何判定逻辑
 * （理由见 [RecipeMapExporter] 的 KDoc：导出侧的"顺手判一下"会变成第二处真相）。
 *
 * ### 输出目录**故意**不可配
 *
 * 服务器进程的 CWD 就是 `run/`（`runServer` 的工作目录），而这里用 `File(".").absoluteFile`，
 * 于是产物落在 `run/recipeaudit-<name>-*.json`。**不要**加 `--out` 之类的参数：调用方给的相对路径
 * 会被解析成"相对已经存在的工作目录"，与它以为的仓库根不是同一个地方，实测直接
 * `FileNotFoundException`（Task 8 踩过）。少一个参数就少一处这种歧义。
 *
 * ### 同步阻塞
 *
 * 命令在服务器线程上同步跑完（assembler 单表实测数百毫秒量级，`all` 四表累加）。
 * 它是开发工具，从 `runServer` 控制台驱动即可；没有做异步——异步会引入"导出中途配方表还在被脚本改"
 * 的窗口，让产物对应不到任何一个确定的配方表状态。
 */
class CommandRecipeAudit : CommandBase()
{

    override fun getName() = "recipeaudit"

    override fun getUsage(sender: ICommandSender) = "gtlitecore.command.recipeaudit.usage"

    /**
     * 表名补全。名字来源与报错列表是**同一个** [RecipeAuditMaps.names]（含 `all`），
     * 否则补全能补出来的和报错列出来的会不一致，使用者又要靠猜。
     *
     * 这里只补最后一个词，正合本命令"单参数"的形态。
     */
    override fun getTabCompletions(server: MinecraftServer, sender: ICommandSender,
                                   args: Array<out String>, targetPos: BlockPos?): MutableList<String>
        = getListOfStringsMatchingLastWord(args, RecipeAuditMaps.names)

    override fun execute(server: MinecraftServer, sender: ICommandSender, args: Array<out String>)
    {
        // 无参数时**也要**把可用表名带上：该 lang 条目里有一个 `%s`
        // （`TextComponentTranslation` 对缺参的 `%s` 是**直接省略**、不抛异常，
        // 于是漏传参数会印出半句话"…map one of "，而使用者恰恰最需要那份列表）。
        val requested = args.getOrNull(0) ?: throw CommandException("gtlitecore.command.recipeaudit.usage",
                                                                    RecipeAuditMaps.names.joinToString(", "))
        // 未知表名必须**响亮失败并列出可用名字**。如果这里改成 `?: return` / 导出一张空表，
        // 使用者会拿到一份看起来正常、内容为空的产物，并把它当成"这张表没有配方"的证据。
        val maps = RecipeAuditMaps.byName(requested)
            ?: throw CommandException("gtlitecore.command.recipeaudit.unknown_map",
                                      requested, RecipeAuditMaps.names.joinToString(", "))

        val startedAt = System.currentTimeMillis()
        val outDir = File(".").absoluteFile
        for ((name, map) in maps)
        {
            // export 内部已 mkdirs；返回的就是写完的两个文件，直接报文件名。
            val (recipesFile, trieFile) = RecipeMapExporter.export(map, name, outDir)
            sender.sendMessage(TextComponentString(
                "[recipeaudit] $name -> ${recipesFile.name}, ${trieFile.name}"))
        }
        sender.sendMessage(TextComponentString(
            "[recipeaudit] 完成，用时 ${System.currentTimeMillis() - startedAt} ms"))
    }
}
