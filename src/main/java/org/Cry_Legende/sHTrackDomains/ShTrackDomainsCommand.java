package org.Cry_Legende.sHTrackDomains;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Commande principale : /shtrackdomains &lt;list|reload|show&gt;
 */
class ShTrackDomainsCommand implements SimpleCommand {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final SHTrackDomains plugin;

    ShTrackDomainsCommand(SHTrackDomains plugin) {
        this.plugin = plugin;
    }

    private static void send(CommandSource source, String legacy) {
        source.sendMessage(LEGACY.deserialize(legacy));
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource sender = invocation.source();
        String[] args = invocation.arguments();

        if (args.length < 1) {
            send(sender, "§cUsage: /shtd <list|reload|show> [domaine]");
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                if (!sender.hasPermission("shtrackdomains.list")) {
                    send(sender, "§cVous n'avez pas la permission.");
                    return;
                }
                if (plugin.totalCount().isEmpty()) {
                    send(sender, "§eAucun domaine n'a encore été enregistré.");
                    return;
                }
                // Tri par nombre total de connexions (decroissant)
                List<Map.Entry<String, Integer>> sorted = new ArrayList<>(plugin.totalCount().entrySet());
                sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

                send(sender, "§aListe des domaines utilisés :");
                for (Map.Entry<String, Integer> entry : sorted) {
                    int online = plugin.onlineCount().getOrDefault(entry.getKey(), 0);
                    send(sender, "§7- §f" + entry.getKey() + " §7: §e" + online
                        + " §aen ligne, §e" + entry.getValue() + " §aau total");
                }
            }
            case "reload" -> {
                if (!sender.hasPermission("shtrackdomains.reload")) {
                    send(sender, "§cVous n'avez pas la permission.");
                    return;
                }
                plugin.dataManager().loadData();
                plugin.recalcOnlineCounts();
                send(sender, "§aDonnées rechargées avec succès.");
            }
            case "show" -> {
                if (!sender.hasPermission("shtrackdomains.show")) {
                    send(sender, "§cVous n'avez pas la permission.");
                    return;
                }
                if (args.length < 2) {
                    send(sender, "§cUsage: /shtd show <domaine>");
                    return;
                }
                String target = args[1].toLowerCase(Locale.ROOT);
                List<String> found = new ArrayList<>();
                for (Player player : plugin.proxy().getAllPlayers()) {
                    SHTrackDomains.domainOf(player)
                        .filter(target::equals)
                        .ifPresent(d -> found.add(player.getUsername()));
                }
                if (found.isEmpty()) {
                    send(sender, "§eAucun joueur n'est connecté via le domaine §f" + target + "§e.");
                } else {
                    send(sender, "§aJoueurs connectés via §f" + target + " §a:");
                    found.forEach(name -> send(sender, "§7- §f" + name));
                }
            }
            default -> send(sender, "§cUsage: /shtd <list|reload|show> [domaine]");
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();

        if (args.length <= 1) {
            String query = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return List.of("list", "reload", "show").stream()
                .filter(s -> s.startsWith(query))
                .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("show")) {
            String query = args[1].toLowerCase(Locale.ROOT);
            return plugin.totalCount().keySet().stream()
                .filter(domain -> domain.startsWith(query))
                .sorted()
                .toList();
        }
        return List.of();
    }

    // La version BungeeCord enregistrait la commande sans permission et filtrait
    // sous-commande par sous-commande. On garde ce comportement.
    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
