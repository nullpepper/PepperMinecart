package stub;

import ltd.pepper.claim.ClaimApi;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class StubPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        ClaimApi.bootstrap();
        getLogger().info("PepperClaim 联动替身已就绪（get() != null）");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean deny = args.length > 0 && "on".equalsIgnoreCase(args[0]);
        ClaimApi.setDenyAll(deny);
        sender.sendMessage("PepperClaim stub deny=" + deny);
        return true;
    }
}
