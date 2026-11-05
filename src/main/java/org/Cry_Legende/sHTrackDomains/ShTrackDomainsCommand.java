package org.Cry_Legende.sHTrackDomains;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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
            send(sender, USAGE);
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "sources" -> {
                AcquisitionStore store = statsStore(sender);
                if (store == null) {
                    return;
                }
                int days = days(args, 1, 30);
                store.newPlayersBySource(days).thenCombine(store.loginsBySource(days), (fresh, logins) -> {
                    send(sender, PREFIX + "Nouveaux joueurs par source, §b" + days + " §7jours §8(venus · inscrits · revenus 2e jour · vus 3 jours+)");
                    if (fresh.isEmpty()) {
                        send(sender, "§8  aucun nouveau joueur enregistre");
                    }
                    fresh.forEach(r -> send(sender, "§7- §f" + r[0] + " §7: §e" + r[1] + " §7· §a" + r[2]
                        + " §7· §b" + r[3] + " §8(" + pct(r[3], r[1]) + ") §7· §d" + r[4]));
                    send(sender, PREFIX + "Connexions par source §8(connexions · joueurs distincts)");
                    logins.forEach(r -> send(sender, "§7- §f" + r[0] + " §7: §e" + r[1] + " §7· §a" + r[2]));
                    return null;
                }).exceptionally(e -> fail(sender, e));
            }
            case "joueur" -> {
                AcquisitionStore store = statsStore(sender);
                if (store == null) {
                    return;
                }
                if (args.length < 2 || !args[1].matches("\\.?\\w{1,16}")) {
                    send(sender, "§cUsage: /shtd joueur <pseudo>");
                    return;
                }
                store.player(args[1]).thenAccept(rows -> {
                    if (rows.isEmpty()) {
                        send(sender, PREFIX + "Aucune connexion enregistree pour §f" + args[1] + "§7.");
                        return;
                    }
                    String[] r = rows.get(0);
                    send(sender, PREFIX + "§f" + r[0] + (r[4].equals("1") ? " §a(nouveau joueur)" : " §8(deja inscrit au premier passage)"));
                    send(sender, "§7- Source : §e" + r[2] + " §8(" + r[1] + ", " + r[3] + ")");
                    send(sender, "§7- Derniere adresse : §f" + r[5] + " §8(" + r[6] + ")");
                    send(sender, "§7- Connexions : §e" + r[7] + " §7sur §e" + r[8] + " §7jour(s)");
                }).thenCompose(ignored -> plugin.playtime() == null
                    ? CompletableFuture.completedFuture(List.<String[]>of())
                    : store.playtime(args[1])
                ).thenAccept(rows -> rows.forEach(t -> send(sender,
                    "§7- Temps sur §f" + t[0] + " §7: §e" + t[1] + " min §8(" + t[2] + " min sur 14 jours)")))
                .exceptionally(e -> fail(sender, e));
            }
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
            default -> send(sender, USAGE);
        }
    }

    private static final String USAGE = "§cUsage: /shtd <list|show|sources|joueur|reload> [domaine|jours|pseudo]";
    private static final String PREFIX = "§7§l« §b§lSH §8§l» §7";

    // Statistiques d'acquisition : meme permission que /shtd list, et la base doit etre active
    private AcquisitionStore statsStore(CommandSource sender) {
        if (!sender.hasPermission("shtrackdomains.list")) {
            send(sender, "§cVous n'avez pas la permission.");
            return null;
        }
        if (plugin.store() == null) {
            send(sender, PREFIX + "Base desactivee : voir §fbase-de-donnees §7dans config.yml.");
            return null;
        }
        return plugin.store();
    }

    private static int days(String[] args, int index, int fallback) {
        if (args.length <= index) {
            return fallback;
        }
        try {
            return Math.clamp(Integer.parseInt(args[index]), 1, 3650);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String pct(String part, String total) {
        long t = Long.parseLong(total);
        return t == 0 ? "-" : Math.round(100.0 * Long.parseLong(part) / t) + " %";
    }

    private static <T> T fail(CommandSource sender, Throwable e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        send(sender, PREFIX + "§cErreur de base : §7" + cause.getMessage());
        return null;
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();

        if (args.length <= 1) {
            String query = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return List.of("list", "show", "sources", "joueur", "reload").stream()
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
