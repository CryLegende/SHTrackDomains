package org.Cry_Legende.sHTrackDomains;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Plugin(
    id = "shtrackdomains",
    name = "SHTrackDomains",
    version = "3.2-velocity",
    description = "Statistiques de connexion et d'acquisition par domaine",
    authors = {"Cry_Legende"}
)
public class SHTrackDomains {

    // Stockage en memoire des statistiques par domaine (cles en minuscule)
    private final ConcurrentHashMap<String, Integer> totalCount = new ConcurrentHashMap<>();
    // Nombre de connexions en cours par domaine (cles en minuscule)
    private final ConcurrentHashMap<String, Integer> onlineCount = new ConcurrentHashMap<>();
    // Joueurs comptes dans onlineCount (par instance) : DisconnectEvent part aussi pour une connexion refusee
    // avant PostLoginEvent ou chassee par un doublon, qui ne doit pas retirer un joueur jamais compte
    private final Set<Player> tracked = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataFolder;

    private DataManager dataManager;
    // Remplacés d'un bloc par /shtrackdomains reload
    private volatile PluginConfig config;
    private volatile Messages messages;
    // null si la base est desactivee ou config.yml illisible : seuls les compteurs de data.yml tournent
    private AcquisitionStore store;
    // null sans base ou sans serveur dans « temps-de-jeu.serveurs »
    private PlaytimeTracker playtime;

    @Inject
    public SHTrackDomains(ProxyServer proxy, Logger logger, @DataDirectory Path dataFolder) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataFolder = dataFolder;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        dataManager = new DataManager(dataFolder, logger, totalCount);
        dataManager.loadData();
        messages = Messages.loadOrDefaults(dataFolder, logger);

        try {
            config = PluginConfig.load(dataFolder, logger);
            if (config.databaseEnabled) {
                store = new AcquisitionStore(config, logger);
                logger.info("Attribution des joueurs active ({} adresses connues).", config.sources.size());
                if (!config.playtimeServers.isEmpty()) {
                    playtime = new PlaytimeTracker(config.playtimeServers, store, ZoneId.systemDefault(),
                        uuid -> proxy.getPlayer(uuid).isPresent());
                    logger.info("Temps de jeu mesure sur : {}.", String.join(", ", config.playtimeServers));
                } else {
                    logger.warn("Temps de jeu non mesure (temps-de-jeu.serveurs vide dans config.yml) : "
                        + "les conditions de temps de jeu des createurs du site ne pourront pas etre remplies.");
                }
            } else {
                logger.info("Base desactivee dans config.yml : attribution des joueurs coupee.");
            }
        } catch (Exception e) {
            logger.error("config.yml illisible : attribution des joueurs coupee, compteurs conserves", e);
        }

        recalcOnlineCounts();

        proxy.getCommandManager().register(
            proxy.getCommandManager().metaBuilder("shtrackdomains").aliases("shtd").plugin(this).build(),
            new ShTrackDomainsCommand(this)
        );

        logger.info("SHTrackDomains active !");

        // Sauvegarde automatique toutes les 5 minutes (identique a la version BungeeCord)
        proxy.getScheduler().buildTask(this, () -> dataManager.saveData())
            .delay(Duration.ofSeconds(300))
            .repeat(Duration.ofSeconds(300))
            .schedule();

        // Temps de jeu des joueurs en ligne ecrit toutes les 5 minutes : un arret brutal en perd au plus 5
        if (playtime != null) {
            proxy.getScheduler().buildTask(this, playtime::checkpoint)
                .delay(Duration.ofSeconds(300))
                .repeat(Duration.ofSeconds(300))
                .schedule();
        }
    }

    /**
     * Résultat de /shtrackdomains reload : le fichier illisible et l'erreur (rien n'a changé), ou la liste des réglages
     * modifiés qui ne se relisent qu'au démarrage du proxy.
     */
    record ReloadResult(String file, String error, List<String> restart) {
    }

    /**
     * Relit config.yml et messages.yml, lus tous les deux avant que quoi que ce soit change. Le domaine, les sources, la
     * table AuthMe, la fenêtre des nouveaux joueurs et les textes sont repris tout de suite ; la base de données et les
     * serveurs de temps de jeu ne se relisent qu'au démarrage. data.yml n'est pas relu : les compteurs en mémoire font foi.
     */
    ReloadResult reload() {
        PluginConfig fresh;
        try {
            fresh = PluginConfig.load(dataFolder, logger);
        } catch (IOException | RuntimeException e) {
            return failed("config.yml", e);
        }
        Messages freshMessages;
        try {
            freshMessages = Messages.load(dataFolder, logger);
        } catch (IOException | RuntimeException e) {
            return failed("messages.yml", e);
        }
        PluginConfig old = config;
        config = fresh;
        messages = freshMessages;
        if (store != null) {
            store.useSettings(fresh);
        }
        List<String> restart = new ArrayList<>();
        if (old != null) {
            if (old.databaseEnabled != fresh.databaseEnabled) restart.add("base-de-donnees.actif");
            if (!old.jdbcUrl.equals(fresh.jdbcUrl)) restart.add("base-de-donnees.url");
            if (!old.dbUser.equals(fresh.dbUser)) restart.add("base-de-donnees.utilisateur");
            if (!old.dbPassword.equals(fresh.dbPassword)) restart.add("base-de-donnees.mot-de-passe");
            if (!old.playtimeServers.equals(fresh.playtimeServers)) restart.add("temps-de-jeu.serveurs");
        }
        logger.info("Configuration et messages rechargés ({} adresses connues).", fresh.sources.size());
        return new ReloadResult(null, null, List.copyOf(restart));
    }

    private ReloadResult failed(String file, Exception e) {
        String error = String.valueOf(e.getMessage()).replace("&", "").replaceAll("\\s+", " ").trim();
        logger.warn("{} illisible, configuration inchangee : {}", file, error);
        return new ReloadResult(file, error, List.of());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (dataManager != null) {
            dataManager.saveDataSync();
        }
        // Avant store.shutdown() : les dernieres lignes passent par le fil SQL, qui finit ses ecritures
        if (playtime != null) {
            playtime.closeAll();
        }
        if (store != null) {
            store.shutdown();
        }
        logger.info("SHTrackDomains desactive !");
    }

    /**
     * Extrait le domaine utilise par le joueur, normalise en minuscule.
     * getHostString() evite toute resolution DNS inverse, contrairement a getHostName().
     */
    static Optional<String> domainOf(Player player) {
        return player.getVirtualHost()
            .map(InetSocketAddress::getHostString)
            .map(host -> host.toLowerCase(Locale.ROOT));
    }

    /**
     * Recalcule le nombre de joueurs en ligne pour chaque domaine au demarrage/reload.
     */
    void recalcOnlineCounts() {
        onlineCount.clear();
        tracked.clear();
        for (Player player : proxy.getAllPlayers()) {
            domainOf(player).ifPresent(domain -> {
                tracked.add(player);
                onlineCount.merge(domain, 1, Integer::sum);
            });
        }
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        domainOf(player).ifPresent(domain -> {
            totalCount.merge(domain, 1, Integer::sum);
            if (tracked.add(player)) {
                onlineCount.merge(domain, 1, Integer::sum);
            }
        });
        if (store != null) {
            String host = PluginConfig.normalizeHost(domainOf(player).orElse(""));
            store.recordLogin(player.getUsername(), player.getUniqueId().toString(), host, config.sourceOf(host));
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        // Joueur deja parti (evenement traite apres sa deconnexion) : pas de segment qui ne serait jamais ferme
        if (playtime != null && player.isActive()) {
            playtime.onServerConnected(player.getUniqueId(), player.getUsername(), event.getServer().getServerInfo().getName());
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        // Avant le test « tracked » : un joueur peut etre sur Opale sans avoir de domaine (adresse absente)
        if (playtime != null) {
            playtime.onDisconnect(event.getPlayer().getUniqueId());
        }
        if (!tracked.remove(event.getPlayer())) {
            return;
        }
        domainOf(event.getPlayer())
            .ifPresent(domain -> onlineCount.computeIfPresent(domain, (k, v) -> (v > 1) ? v - 1 : null));
    }

    Messages messages() {
        return messages;
    }

    ProxyServer proxy() {
        return proxy;
    }

    ConcurrentHashMap<String, Integer> totalCount() {
        return totalCount;
    }

    ConcurrentHashMap<String, Integer> onlineCount() {
        return onlineCount;
    }

    DataManager dataManager() {
        return dataManager;
    }

    AcquisitionStore store() {
        return store;
    }

    PlaytimeTracker playtime() {
        return playtime;
    }
}
