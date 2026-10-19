package smokehooks;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** 冒烟用的"捣乱"插件：可以让所有掉落物品的生成被取消，用来验证交付失败时插件不丢东西。 */
public final class SmokeHooksPlugin extends JavaPlugin implements Listener {

    private static volatile boolean cancelDrops;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        cancelDrops = args.length > 0 && "on".equalsIgnoreCase(args[0]);
        sender.sendMessage("SmokeHooks cancelDrops=" + cancelDrops);
        return true;
    }

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent event) {
        if (cancelDrops) {
            event.setCancelled(true);
        }
    }
}
