package org.Cry_Legende.sHTrackDomains;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Temps de jeu par joueur, jour et serveur (table sh_acq_playtime), pour les serveurs listes dans
 * « temps-de-jeu.serveurs ». Un segment s'ouvre a l'arrivee sur un serveur suivi et se ferme au
 * changement de serveur ou a la deconnexion ; la tache periodique ecrit les segments ouverts et les
 * repart de maintenant, si bien qu'un arret brutal du proxy perd au plus une periode.
 * Les acces a « open » se font cle par cle (put, remove, replace) : un segment n'est jamais compte deux fois.
 */
final class PlaytimeTracker {

    /** Segment ouvert : pseudo en minuscules, serveur suivi, debut en millisecondes. */
    record Segment(String name, String server, long startMillis) {
    }

    /** Ligne a ajouter : secondes jouees un jour donne sur un serveur. */
    record Row(String name, LocalDate day, String server, long seconds) {
    }

    private final Set<String> servers;
    private final AcquisitionStore store;
    private final ZoneId zone;
    private final Map<UUID, Segment> open = new ConcurrentHashMap<>();

    PlaytimeTracker(Set<String> servers, AcquisitionStore store, ZoneId zone) {
        this.servers = servers;
        this.store = store;
        this.zone = zone;
    }

    boolean tracks(String server) {
        return server != null && servers.contains(server.toLowerCase(Locale.ROOT));
    }

    /** Arrivee sur un serveur : ferme le segment precedent, ouvre le nouveau si ce serveur est suivi. */
    void onServerConnected(UUID uuid, String username, String server) {
        long now = System.currentTimeMillis();
        Segment previous = tracks(server)
            ? open.put(uuid, new Segment(username.toLowerCase(Locale.ROOT), server.toLowerCase(Locale.ROOT), now))
            : open.remove(uuid);
        if (previous != null) {
            store.addPlaytime(split(previous, now));
        }
    }

    void onDisconnect(UUID uuid) {
        Segment previous = open.remove(uuid);
        if (previous != null) {
            store.addPlaytime(split(previous, System.currentTimeMillis()));
        }
    }

    /** Tache periodique : ecrit le temps des segments ouverts et les fait repartir de maintenant. */
    void checkpoint() {
        long now = System.currentTimeMillis();
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<UUID, Segment> entry : open.entrySet()) {
            Segment segment = entry.getValue();
            // replace() echoue si le joueur vient de changer de serveur ou de partir : ce segment-la est deja ecrit
            if (open.replace(entry.getKey(), segment, new Segment(segment.name(), segment.server(), now))) {
                rows.addAll(split(segment, now));
            }
        }
        store.addPlaytime(rows);
    }

    /** Arret du proxy : tout ce qui est ouvert est ecrit (avant la fermeture du fil SQL). */
    void closeAll() {
        long now = System.currentTimeMillis();
        List<Row> rows = new ArrayList<>();
        for (UUID uuid : List.copyOf(open.keySet())) {
            Segment segment = open.remove(uuid);
            if (segment != null) {
                rows.addAll(split(segment, now));
            }
        }
        store.addPlaytime(rows);
    }

    int openSegments() {
        return open.size();
    }

    /** Decoupe un segment par jour (heure locale du serveur, comme les DATETIME des autres tables). */
    List<Row> split(Segment segment, long endMillis) {
        List<Row> rows = new ArrayList<>(1);
        long start = segment.startMillis();
        while (start < endMillis) {
            ZonedDateTime startTime = Instant.ofEpochMilli(start).atZone(zone);
            LocalDate day = startTime.toLocalDate();
            long midnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            long end = Math.min(endMillis, midnight);
            long seconds = (end - start) / 1000;
            if (seconds > 0) {
                rows.add(new Row(segment.name(), day, segment.server(), seconds));
            }
            start = end;
        }
        return rows;
    }
}
