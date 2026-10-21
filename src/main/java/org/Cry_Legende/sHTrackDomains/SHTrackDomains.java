package org.Cry_Legende.sHTrackDomains;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Plugin(
    id = "shtrackdomains",
    name = "SHTrackDomains",
    version = "2.0-velocity",
    description = "Statistiques de connexion par domaine",
    authors = {"Cry_Legende"}
)
public class SHTrackDomains {

    // Stockage en memoire des statistiques par domaine (cles en minuscule)
    private final ConcurrentHashMap<String, Integer> totalCount = new ConcurrentHashMap<>();
    // Nombre de connexions en cours par domaine (cles en minuscule)
    private final ConcurrentHashMap<String, Integer> onlineCount = new ConcurrentHashMap<>();

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataFolder;

    private DataManager dataManager;

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
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        dataManager.saveDataSync();
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
        for (Player player : proxy.getAllPlayers()) {
            domainOf(player).ifPresent(domain -> onlineCount.merge(domain, 1, Integer::sum));
        }
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        domainOf(event.getPlayer()).ifPresent(domain -> {
            totalCount.merge(domain, 1, Integer::sum);
            onlineCount.merge(domain, 1, Integer::sum);
        });
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        domainOf(event.getPlayer())
            .ifPresent(domain -> onlineCount.computeIfPresent(domain, (k, v) -> (v > 1) ? v - 1 : null));
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
}
