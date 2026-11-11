package org.Cry_Legende.sHTrackDomains;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Textes de messages.yml (dans le dossier de données). Une clé absente du fichier reprend le texte livré dans le jar.
 * Codes couleur &amp;, {prefix} est remplacé par la clé prefix, et les valeurs insérées par {nom} ne sont jamais lues
 * comme des codes couleur (une adresse tapée par un joueur ne peut pas colorer le message).
 */
final class Messages {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final Map<String, String> texts;

    private Messages(Map<String, String> texts) {
        this.texts = texts;
    }

    /**
     * Lit messages.yml, créé avec les textes du jar s'il manque. Un fichier illisible lève une exception : l'appelant
     * garde alors ses textes actuels.
     */
    static Messages load(Path dataFolder, Logger logger) throws IOException {
        Path file = dataFolder.resolve("messages.yml");
        if (!Files.exists(file)) {
            Files.createDirectories(dataFolder);
            try (InputStream in = Messages.class.getResourceAsStream("/messages.yml")) {
                if (in == null) {
                    throw new IOException("messages.yml absent du jar");
                }
                Files.copy(in, file);
            }
            logger.info("messages.yml cree avec les textes par defaut.");
        }
        Map<String, String> merged = new HashMap<>(jarTexts());
        merged.putAll(read(file));
        return new Messages(Map.copyOf(merged));
    }

    /** Comme {@link #load}, mais un fichier illisible donne les textes du jar (démarrage). */
    static Messages loadOrDefaults(Path dataFolder, Logger logger) {
        try {
            return load(dataFolder, logger);
        } catch (IOException | RuntimeException e) {
            logger.error("messages.yml illisible, textes par defaut utilises", e);
            try {
                return new Messages(Map.copyOf(jarTexts()));
            } catch (IOException ioe) {
                return new Messages(Map.of());
            }
        }
    }

    private static Map<String, String> jarTexts() throws IOException {
        try (InputStream in = Messages.class.getResourceAsStream("/messages.yml")) {
            if (in == null) {
                throw new IOException("messages.yml absent du jar");
            }
            try (Reader reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
                return flatten(new Yaml().load(reader));
            }
        }
    }

    private static Map<String, String> read(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return flatten(new Yaml().load(reader));
        }
    }

    private static Map<String, String> flatten(Object loaded) {
        Map<String, String> out = new HashMap<>();
        if (loaded instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (value != null) {
                    out.put(String.valueOf(key), String.valueOf(value));
                }
            });
        }
        return out;
    }

    /** Texte brut (codes &amp; non appliqués), {prefix} remplacé. */
    String raw(String key) {
        String text = texts.getOrDefault(key, key);
        return text.replace("{prefix}", texts.getOrDefault("prefix", ""));
    }

    /** Message prêt à envoyer ; chaque paire de {@code values} remplace {nom} par sa valeur. */
    Component get(String key, String... values) {
        Component message = LEGACY.deserialize(raw(key));
        for (int i = 0; i + 1 < values.length; i += 2) {
            message = message.replaceText(TextReplacementConfig.builder()
                .matchLiteral("{" + values[i] + "}").replacement(values[i + 1]).build());
        }
        return message;
    }
}
