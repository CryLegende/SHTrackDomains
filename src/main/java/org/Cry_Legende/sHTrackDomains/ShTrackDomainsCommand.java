package org.Cry_Legende.sHTrackDomains;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Commande principale : /shtrackdomains &lt;list|show|sources|player|reload&gt; (alias /shtd, ancien nom
 * de player : joueur).
 *
 * <p>Chaque sous-commande a sa permission shtrackdomains.admin.&lt;sous-commande&gt;, et shtrackdomains.admin les donne
 * toutes (Velocity ne déclare pas de nœuds : le parent est testé ici).</p>
 */
class ShTrackDomainsCommand implements SimpleCommand {

    private static final List<String> SUBCOMMANDS = List.of("list", "show", "sources", "player", "reload");
    private static final String ADMIN_PERMISSION = "shtrackdomains.admin";

    private final SHTrackDomains plugin;

    ShTrackDomainsCommand(SHTrackDomains plugin) {
        this.plugin = plugin;
    }

    private Messages texts() {
        return plugin.messages();
    }

    private void send(CommandSource source, String key, String... values) {
        source.sendMessage(texts().get(key, values));
    }

    /** Sous-commande que ce mot désigne (joueur est l'ancien nom de player), ou null. */
    private static String canonical(String word) {
        String sub = word.toLowerCase(Locale.ROOT);
        if (sub.equals("joueur")) {
            return "player";
        }
        return SUBCOMMANDS.contains(sub) ? sub : null;
    }

    private static boolean can(CommandSource source, String sub) {
        return source.hasPermission(ADMIN_PERMISSION + "." + sub) || source.hasPermission(ADMIN_PERMISSION);
    }

    private static List<String> allowed(CommandSource source) {
        return SUBCOMMANDS.stream().filter(sub -> can(source, sub)).toList();
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource sender = invocation.source();
        String[] args = invocation.arguments();

        List<String> allowed = allowed(sender);
        if (allowed.isEmpty()) {
            send(sender, "no-permission");
            return;
        }
        String sub = args.length < 1 ? null : canonical(args[0]);
        if (sub == null) {
            send(sender, "usage", "subcommands", String.join("|", allowed));
            return;
        }
        if (!allowed.contains(sub)) {
            send(sender, "no-permission");
            return;
        }

        switch (sub) {
            case "sources" -> {
                AcquisitionStore store = statsStore(sender);
                if (store == null) {
                    return;
                }
                int days = days(args, 1, 30);
                store.newPlayersBySource(days).thenCombine(store.loginsBySource(days), (fresh, logins) -> {
                    send(sender, "sources-header", "days", String.valueOf(days));
                    if (fresh.isEmpty()) {
                        send(sender, "sources-empty");
                    }
                    fresh.forEach(r -> send(sender, "sources-row", "source", r[0], "new", r[1], "registered", r[2],
                        "returned", r[3], "percent", pct(r[3], r[1]), "seen", r[4]));
                    send(sender, "sources-logins-header");
                    logins.forEach(r -> send(sender, "sources-logins-row", "source", r[0], "logins", r[1], "players", r[2]));
                    return null;
                }).exceptionally(e -> fail(sender, e));
            }
            case "player" -> {
                AcquisitionStore store = statsStore(sender);
                if (store == null) {
                    return;
                }
                if (args.length < 2 || !args[1].matches("\\.?\\w{1,16}")) {
                    send(sender, "usage-player");
                    return;
                }
                store.player(args[1]).thenAccept(rows -> {
                    if (rows.isEmpty()) {
                        send(sender, "player-none", "player", args[1]);
                        return;
                    }
                    String[] r = rows.get(0);
                    send(sender, r[4].equals("1") ? "player-new" : "player-known", "player", r[0]);
                    send(sender, "player-source", "source", r[2], "host", r[1], "date", r[3]);
                    send(sender, "player-last", "host", r[5], "date", r[6]);
                    send(sender, "player-logins", "logins", r[7], "days", r[8]);
                }).thenCompose(ignored -> plugin.playtime() == null
                    ? CompletableFuture.completedFuture(List.<String[]>of())
                    : store.playtime(args[1])
                ).thenAccept(rows -> rows.forEach(t -> send(sender, "player-time",
                    "server", t[0], "minutes", t[1], "minutes-14", t[2])))
                .exceptionally(e -> fail(sender, e));
            }
            case "list" -> {
                if (plugin.totalCount().isEmpty()) {
                    send(sender, "list-empty");
                    return;
                }
                // Tri par nombre total de connexions (decroissant)
                List<Map.Entry<String, Integer>> sorted = new ArrayList<>(plugin.totalCount().entrySet());
                sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

                send(sender, "list-header");
                for (Map.Entry<String, Integer> entry : sorted) {
                    int online = plugin.onlineCount().getOrDefault(entry.getKey(), 0);
                    send(sender, "list-row", "domain", entry.getKey(), "online", String.valueOf(online),
                        "total", String.valueOf(entry.getValue()));
                }
            }
            case "reload" -> {
                SHTrackDomains.ReloadResult result = plugin.reload();
                if (result.error() != null) {
                    send(sender, "reload-error", "file", result.file(), "error", result.error());
                    return;
                }
                send(sender, "reload-done");
                if (!result.restart().isEmpty()) {
                    send(sender, "reload-restart", "keys", String.join(", ", result.restart()));
                }
            }
            case "show" -> {
                if (args.length < 2) {
                    send(sender, "usage-show");
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
                    send(sender, "show-none", "domain", target);
                } else {
                    send(sender, "show-header", "domain", target);
                    found.forEach(name -> send(sender, "show-row", "player", name));
                }
            }
            default -> send(sender, "usage", "subcommands", String.join("|", allowed));
        }
    }

    // Statistiques d'acquisition : la base doit etre active
    private AcquisitionStore statsStore(CommandSource sender) {
        if (plugin.store() == null) {
            send(sender, "database-disabled");
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

    private <T> T fail(CommandSource sender, Throwable e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        send(sender, "database-error", "error", String.valueOf(cause.getMessage()));
        return null;
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length <= 1) {
            String query = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return allowed(source).stream()
                .filter(s -> s.startsWith(query))
                .toList();
        }
        if (args.length == 2 && "show".equals(canonical(args[0])) && can(source, "show")) {
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
