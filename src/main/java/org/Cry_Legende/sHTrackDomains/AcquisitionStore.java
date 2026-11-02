package org.Cry_Legende.sHTrackDomains;

import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Attribution des joueurs (tables sh_acq_players et sh_acq_logins de la base Global).
 * Tout passe par un seul fil dedie : aucune requete sur les fils de Velocity, et une seule
 * connexion, rouverte si MariaDB l'a fermee.
 */
final class AcquisitionStore {

    private final PluginConfig config;
    private final Logger logger;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SHTrackDomains-DB");
        thread.setDaemon(true);
        return thread;
    });
    // Pilote relocalise par le shade : instancie directement, DriverManager ne le verrait pas
    private final Driver driver = new org.mariadb.jdbc.Driver();
    private Connection connection;

    AcquisitionStore(PluginConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    /**
     * Enregistre une connexion. La premiere connexion vue d'un pseudo fixe sa source ; il compte comme
     * nouveau joueur s'il n'a pas de compte AuthMe ou si ce compte a moins de « nouveau-joueur-heures ».
     */
    void recordLogin(String username, String uuid, String host, String source) {
        LocalDateTime now = LocalDateTime.now();
        executor.execute(() -> {
            try {
                insertLogin(username, uuid, host, source, now);
            } catch (SQLException e) {
                logger.warn("Connexion de {} non enregistree ({}) : {}", username, source, e.getMessage());
                closeQuietly();
            }
        });
    }

    private void insertLogin(String username, String uuid, String host, String source, LocalDateTime now) throws SQLException {
        Connection c = connection();
        String key = username.toLowerCase(Locale.ROOT);
        Timestamp ts = Timestamp.valueOf(now);

        boolean newPlayer = true;
        try (PreparedStatement ps = c.prepareStatement("SELECT regdate FROM " + config.authmeTable + " WHERE username = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long windowStart = System.currentTimeMillis() - config.newPlayerWindowHours * 3_600_000L;
                    newPlayer = rs.getLong(1) >= windowStart;
                }
            }
        }

        // days_seen est mis a jour avant last_seen : MariaDB applique les affectations dans l'ordre
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO sh_acq_players (name, display_name, uuid, first_host, first_source, first_seen, new_player, last_host, last_seen)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE days_seen = days_seen + (DATE(last_seen) <> DATE(VALUES(last_seen))),"
                + " logins = logins + 1, display_name = VALUES(display_name), uuid = VALUES(uuid),"
                + " last_host = VALUES(last_host), last_seen = VALUES(last_seen)")) {
            ps.setString(1, key);
            ps.setString(2, username);
            ps.setString(3, uuid);
            ps.setString(4, host);
            ps.setString(5, source);
            ps.setTimestamp(6, ts);
            ps.setBoolean(7, newPlayer);
            ps.setString(8, host);
            ps.setTimestamp(9, ts);
            ps.executeUpdate();
        }

        try (PreparedStatement ps = c.prepareStatement("INSERT INTO sh_acq_logins (ts, name, host, source) VALUES (?, ?, ?, ?)")) {
            ps.setTimestamp(1, ts);
            ps.setString(2, key);
            ps.setString(3, host);
            ps.setString(4, source);
            ps.executeUpdate();
        }
    }

    /** Nouveaux joueurs par source sur N jours : venus, inscrits, revenus un 2e jour, vus 3 jours ou plus. */
    CompletableFuture<List<String[]>> newPlayersBySource(int days) {
        return query("SELECT p.first_source, COUNT(*), SUM(a.username IS NOT NULL), SUM(p.days_seen >= 2), SUM(p.days_seen >= 3)"
            + " FROM sh_acq_players p LEFT JOIN " + config.authmeTable + " a ON a.username = p.name"
            + " WHERE p.new_player = 1 AND p.first_seen >= NOW() - INTERVAL ? DAY"
            + " GROUP BY p.first_source ORDER BY 2 DESC LIMIT 25", days);
    }

    /** Connexions (tous joueurs) par source sur N jours : connexions, joueurs distincts. */
    CompletableFuture<List<String[]>> loginsBySource(int days) {
        return query("SELECT source, COUNT(*), COUNT(DISTINCT name) FROM sh_acq_logins"
            + " WHERE ts >= NOW() - INTERVAL ? DAY GROUP BY source ORDER BY 2 DESC LIMIT 25", days);
    }

    /** Parrainage : nouveaux joueurs venus par <pseudo>.skyhand.fr d'un joueur deja inscrit. */
    CompletableFuture<List<String[]>> referrals(int days) {
        return query("SELECT p.first_source, COUNT(*), SUM(p.days_seen >= 2), SUM(p.days_seen >= 3)"
            + " FROM sh_acq_players p JOIN " + config.authmeTable + " a ON a.username = p.first_source"
            + " AND a.regdate < UNIX_TIMESTAMP(p.first_seen) * 1000"
            + " WHERE p.new_player = 1 AND p.first_seen >= NOW() - INTERVAL ? DAY"
            + " GROUP BY p.first_source ORDER BY 2 DESC LIMIT 20", days);
    }

    /** Fiche d'un joueur. */
    CompletableFuture<List<String[]>> player(String username) {
        return query("SELECT display_name, first_host, first_source, first_seen, new_player, last_host, last_seen, logins, days_seen"
            + " FROM sh_acq_players WHERE name = ?", username.toLowerCase(Locale.ROOT));
    }

    private CompletableFuture<List<String[]>> query(String sql, Object param) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Connection c = connection();
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setObject(1, param);
                    try (ResultSet rs = ps.executeQuery()) {
                        ResultSetMetaData meta = rs.getMetaData();
                        List<String[]> rows = new ArrayList<>();
                        while (rs.next()) {
                            String[] row = new String[meta.getColumnCount()];
                            for (int i = 0; i < row.length; i++) {
                                row[i] = rs.getString(i + 1);
                            }
                            rows.add(row);
                        }
                        return rows;
                    }
                }
            } catch (SQLException e) {
                closeQuietly();
                throw new IllegalStateException(e.getMessage(), e);
            }
        }, executor);
    }

    private Connection connection() throws SQLException {
        if (connection != null && connection.isValid(2)) {
            return connection;
        }
        closeQuietly();
        Properties props = new Properties();
        props.setProperty("user", config.dbUser);
        props.setProperty("password", config.dbPassword);
        props.setProperty("connectTimeout", "5000");
        props.setProperty("socketTimeout", "15000");
        connection = driver.connect(config.jdbcUrl, props);
        if (connection == null) {
            throw new SQLException("URL JDBC non reconnue : " + config.jdbcUrl);
        }
        return connection;
    }

    private void closeQuietly() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // connexion deja perdue
            }
            connection = null;
        }
    }

    /** Arret du proxy : on laisse finir les ecritures en attente (5 s au plus). */
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Ecritures d'acquisition encore en attente a l'arret, abandonnees.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeQuietly();
    }
}
