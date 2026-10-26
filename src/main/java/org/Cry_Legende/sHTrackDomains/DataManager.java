package org.Cry_Legende.sHTrackDomains;

import org.slf4j.Logger;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistance dans data.yml. Format identique a la version BungeeCord :
 * les points des noms de domaine sont encodes en "___" pour ne pas creer
 * de sections YAML imbriquees.
 */
class DataManager {

    private final Path dataFile;
    private final Logger logger;
    private final ConcurrentHashMap<String, Integer> totalCount;

    DataManager(Path dataFolder, Logger logger, ConcurrentHashMap<String, Integer> totalCount) {
        this.dataFile = dataFolder.resolve("data.yml");
        this.logger = logger;
        this.totalCount = totalCount;
        try {
            Files.createDirectories(dataFolder);
        } catch (IOException e) {
            logger.error("Impossible de creer le dossier de donnees", e);
        }
    }

    private static String toSafeKey(String key) {
        return key.replace(".", "___");
    }

    private static String fromSafeKey(String safeKey) {
        return safeKey.replace("___", ".");
    }

    private static Yaml yaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options);
    }

    @SuppressWarnings("unchecked")
    synchronized void loadData() {
        if (!Files.exists(dataFile)) {
            totalCount.clear();
            logger.info("Aucun data.yml existant, demarrage a vide.");
            return;
        }
        try (Reader reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
            Object loaded = yaml().load(reader);
            if (!(loaded instanceof Map<?, ?> root)) {
                totalCount.clear();
                logger.info("data.yml vide.");
                return;
            }
            // Lecture complete avant de toucher a la memoire : un fichier illisible ne vide pas les compteurs
            Map<String, Integer> parsed = new HashMap<>();
            Object section = root.get("totalCount");
            if (section instanceof Map<?, ?> totals) {
                for (Map.Entry<?, ?> entry : ((Map<Object, Object>) totals).entrySet()) {
                    // On normalise le domaine en minuscule des le chargement et on fusionne
                    // les anciennes cles de casse differente (ex: MC3 et mc3).
                    String domain = fromSafeKey(String.valueOf(entry.getKey())).toLowerCase(Locale.ROOT);
                    int count = (entry.getValue() instanceof Number n) ? n.intValue() : 0;
                    parsed.merge(domain, count, Integer::sum);
                }
            }
            totalCount.clear();
            totalCount.putAll(parsed);
            logger.info("Fichier data.yml charge et donnees fusionnees ({} domaines).", totalCount.size());
        } catch (Exception e) {
            logger.error("Erreur lors du chargement de data.yml", e);
            // La prochaine sauvegarde reecrira data.yml : on en garde une copie pour une reparation a la main
            try {
                Path backup = dataFile.resolveSibling("data.yml.corrupt-" + System.currentTimeMillis());
                Files.copy(dataFile, backup, StandardCopyOption.REPLACE_EXISTING);
                logger.warn("data.yml illisible copie vers {}", backup.getFileName());
            } catch (IOException ioe) {
                logger.error("Copie de sauvegarde de data.yml impossible", ioe);
            }
        }
    }

    void saveData() {
        CompletableFuture.runAsync(this::saveDataSync);
    }

    // synchronized : la sauvegarde periodique et celle de l'arret partagent le meme fichier temporaire
    synchronized void saveDataSync() {
        try {
            Map<String, Integer> safeMap = new LinkedHashMap<>();
            totalCount.forEach((domain, count) -> safeMap.put(toSafeKey(domain), count));

            Map<String, Object> root = new LinkedHashMap<>();
            root.put("totalCount", safeMap);

            Path tmp = dataFile.resolveSibling("data.yml.tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                yaml().dump(root, writer);
            }
            Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING);
            logger.info("Donnees sauvegardees dans data.yml.");
        } catch (Exception e) {
            logger.error("Erreur lors de la sauvegarde de data.yml", e);
        }
    }
}
