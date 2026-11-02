package org.Cry_Legende.sHTrackDomains;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * config.yml : adresses connues -> source, et acces a la base des statistiques d'acquisition.
 * Le fichier par defaut (dans le jar) documente chaque cle.
 */
final class PluginConfig {

    private static final int MAX_HOST = 255;
    private static final int MAX_SOURCE = 64;

    final String baseDomain;
    final Map<String, String> sources;
    final boolean databaseEnabled;
    final String jdbcUrl;
    final String dbUser;
    final String dbPassword;
    final String authmeTable;
    final int newPlayerWindowHours;

    private PluginConfig(Map<?, ?> root) {
        this.baseDomain = normalizeHost(String.valueOf(get(root, "domaine", "skyhand.fr")));

        Map<String, String> map = new LinkedHashMap<>();
        if (root.get("sources") instanceof Map<?, ?> raw) {
            raw.forEach((host, label) -> map.put(normalizeHost(String.valueOf(host)), clip(String.valueOf(label), MAX_SOURCE)));
        }
        this.sources = Map.copyOf(map);

        Map<?, ?> db = root.get("base-de-donnees") instanceof Map<?, ?> m ? m : Map.of();
        this.databaseEnabled = Boolean.parseBoolean(String.valueOf(get(db, "actif", "false")));
        this.jdbcUrl = String.valueOf(get(db, "url", "jdbc:mariadb://127.0.0.1:3306/Global"));
        this.dbUser = String.valueOf(get(db, "utilisateur", "shtrackdomains"));
        this.dbPassword = String.valueOf(get(db, "mot-de-passe", ""));
        // Nom de table interpole dans les requetes : on n'accepte qu'un identifiant simple
        String table = String.valueOf(get(db, "table-authme", "authme"));
        this.authmeTable = table.matches("[A-Za-z0-9_]{1,64}") ? table : "authme";
        Object window = get(db, "nouveau-joueur-heures", 168);
        this.newPlayerWindowHours = window instanceof Number n ? Math.max(1, n.intValue()) : 168;
    }

    static PluginConfig load(Path dataFolder, Logger logger) throws IOException {
        Path file = dataFolder.resolve("config.yml");
        if (!Files.exists(file)) {
            Files.createDirectories(dataFolder);
            try (InputStream in = PluginConfig.class.getResourceAsStream("/config.yml")) {
                if (in == null) {
                    throw new IOException("config.yml absent du jar");
                }
                Files.copy(in, file);
            }
            logger.info("config.yml cree avec les valeurs par defaut.");
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object loaded = new Yaml().load(reader);
            return new PluginConfig(loaded instanceof Map<?, ?> root ? root : Map.of());
        }
    }

    /**
     * Adresse tapee par le joueur, normalisee : minuscules, sans point final ni marqueur Forge.
     */
    static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String h = host;
        int nul = h.indexOf('\0');
        if (nul >= 0) {
            h = h.substring(0, nul);
        }
        h = h.trim().toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        return clip(h, MAX_HOST);
    }

    /**
     * Source d'une adresse : la table « sources », sinon le sous-domaine direct du domaine de base
     * (code createur ou campagne), sinon l'adresse elle-meme.
     */
    String sourceOf(String host) {
        String h = normalizeHost(host);
        if (h.isEmpty()) {
            return "inconnue";
        }
        String label = sources.get(h);
        if (label != null) {
            return label;
        }
        String suffix = "." + baseDomain;
        if (h.endsWith(suffix)) {
            String sub = h.substring(0, h.length() - suffix.length());
            if (!sub.isEmpty() && sub.indexOf('.') < 0) {
                return clip(sub, MAX_SOURCE);
            }
        }
        return clip(h, MAX_SOURCE);
    }

    private static Object get(Map<?, ?> map, String key, Object fallback) {
        Object value = map.get(key);
        return value != null ? value : fallback;
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
