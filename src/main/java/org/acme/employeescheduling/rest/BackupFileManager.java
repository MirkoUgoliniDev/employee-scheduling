package org.acme.employeescheduling.rest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BackupFileManager {

    private static final Logger logger = Logger.getLogger(BackupFileManager.class.getName());

    private BackupFileManager() {}

    static Map<String, Object> describe(Path p, Pattern namePattern) {
        Map<String, Object> info = new LinkedHashMap<>();
        String name = p.getFileName().toString();
        info.put("filename", name);
        Matcher m = namePattern.matcher(name);
        if (m.matches()) {
            String raw = m.group(2);
            info.put("timestamp", raw.substring(0, 4) + "-" + raw.substring(4, 6) + "-" + raw.substring(6, 8)
                    + " " + raw.substring(9, 11) + ":" + raw.substring(11, 13) + ":" + raw.substring(13, 15));
            info.put("tag", m.group(3));
        }
        try {
            info.put("size", Files.size(p));
        } catch (Exception e) {
            info.put("size", 0);
        }
        return info;
    }

    static void rotate(Path backupDir, Pattern namePattern, int autoKeep, int otherKeep,
                       int autoRetentionDays, int otherRetentionDays) {
        Map<String, List<Path>> byTag = new LinkedHashMap<>();
        if (!Files.isDirectory(backupDir, LinkOption.NOFOLLOW_LINKS)) return;
        try (var files = Files.list(backupDir)) {
            files.forEach(p -> {
                Matcher m = namePattern.matcher(p.getFileName().toString());
                if (m.matches() && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    byTag.computeIfAbsent(m.group(3), k -> new ArrayList<>()).add(p);
            });
            for (var entry : byTag.entrySet()) {
                boolean automatic = "auto".equals(entry.getKey());
                int keep = automatic ? autoKeep : otherKeep;
                int retentionDays = automatic ? autoRetentionDays : otherRetentionDays;
                List<Path> tagged = entry.getValue();
                tagged.sort(Comparator.comparingLong(BackupFileManager::lastModifiedMillis).reversed()
                        .thenComparing(p -> p.getFileName().toString(), Comparator.reverseOrder()));
                long cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L;
                Path newest = tagged.isEmpty() ? null : tagged.get(0);
                int idx = 0;
                for (Path p : tagged) {
                    if (idx >= keep && !p.equals(newest)
                            || lastModifiedMillis(p) < cutoff && !p.equals(newest)) {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {}
                    }
                    idx++;
                }
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Errore nella rotazione dei backup", e);
        }
    }

    private static long lastModifiedMillis(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0; }
    }
}