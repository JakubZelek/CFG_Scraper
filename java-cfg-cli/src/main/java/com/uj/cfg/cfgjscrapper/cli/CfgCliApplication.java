package com.uj.cfg.cfgjscrapper.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uj.cfg.cfgjscrapper.cfg.CFGExtractor;
import com.uj.cfg.cfgjscrapper.cfg.ClassCfg;
import com.uj.cfg.cfgjscrapper.cfg.MethodCfg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class CfgCliApplication {

    private static final Logger log = LoggerFactory.getLogger(CfgCliApplication.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{|\\}|\\b(class|interface|enum|record)\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final List<String> SKIP_DIR_NAMES = List.of(".git", ".idea", "target", "build", ".gradle", ".mvn", ".cfg-java-cache", ".cfg-java-classes");

    public static void main(String[] args) throws Exception {
        Thread.sleep(20000);
        CliArgs cliArgs = CliArgs.parse(args);

        Path repoRoot = Path.of(cliArgs.repoRoot()).toAbsolutePath().normalize();
        Path sourceFile = cliArgs.filepath() == null ? null : Path.of(cliArgs.filepath()).toAbsolutePath().normalize();
        Path cacheDir = cliArgs.cacheDir() == null ? null : Path.of(cliArgs.cacheDir()).toAbsolutePath().normalize();

        if (!Files.isDirectory(repoRoot)) {
            throw new IllegalArgumentException("Repository root does not exist: " + repoRoot);
        }
        log.info("Starting Java CFG extraction for repoRoot={} (mode={})",
                repoRoot,
                sourceFile == null ? "cache" : "single-file");

        Map<Path, SourceFileInfo> sourceIndex = indexSourceFiles(repoRoot);
        log.info("Indexed {} Java source files", sourceIndex.size());
        Map<Path, RepoFileCfg> grouped = groupCfgsBySourceFile(repoRoot, sourceIndex);
        log.info("Prepared grouped CFG payloads for {} files", grouped.size());

        if (cacheDir != null) {
            writeCache(repoRoot, cacheDir, grouped);
            log.info("Wrote CFG cache to {}", cacheDir);
        }

        if (sourceFile != null) {
            RepoFileCfg payload = grouped.getOrDefault(sourceFile, new RepoFileCfg(sourceFile.toString(), List.of()));
            log.info("Returning CFG payload for {} with {} graphs", sourceFile, payload.getGraphs().size());
            log.warn(MAPPER.writeValueAsString(payload));
        }
    }

    private static Map<Path, RepoFileCfg> groupCfgsBySourceFile(Path repoRoot, Map<Path, SourceFileInfo> sourceIndex) throws IOException {
        CFGExtractor extractor = new CFGExtractor();
        List<ClassCfg> classCfgs = extractor.extractFromClasses(repoRoot);
        log.info("Extractor returned {} class CFG entries", classCfgs.size());

        Map<String, Path> fqcnToSource = new LinkedHashMap<>();
        Map<Path, RepoFileCfg> grouped = new LinkedHashMap<>();

        for (Map.Entry<Path, SourceFileInfo> entry : sourceIndex.entrySet()) {
            grouped.put(entry.getKey(), new RepoFileCfg(entry.getKey().toString(), new ArrayList<>()));
            for (String fqcn : entry.getValue().declaredTypes()) {
                fqcnToSource.put(fqcn, entry.getKey());
            }
        }

        for (ClassCfg classCfg : classCfgs) {
            String className = classCfg.getClassName();
            if (className == null || className.isBlank()) {
                continue;
            }
            String sourceLookup = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
            Path sourcePath = fqcnToSource.get(sourceLookup);
            if (sourcePath == null) {
                continue;
            }
            RepoFileCfg repoFileCfg = grouped.get(sourcePath);
            if (repoFileCfg != null) {
                repoFileCfg.getGraphs().addAll(classCfg.getGraphs());
            }
        }

        for (RepoFileCfg repoFileCfg : grouped.values()) {
            repoFileCfg.getGraphs().sort(Comparator.comparing(MethodCfg::getName));
        }

        return grouped;
    }

    private static Map<Path, SourceFileInfo> indexSourceFiles(Path repoRoot) throws IOException {
        Map<Path, SourceFileInfo> index = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(repoRoot)) {
            List<Path> sources = walk
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !shouldSkip(path))
                    .sorted()
                    .toList();
            for (Path source : sources) {
                SourceFileInfo info = parseSourceFile(source);
                index.put(source.toAbsolutePath().normalize(), info);
            }
        }
        return index;
    }

    private static boolean shouldSkip(Path path) {
        for (Path part : path) {
            if (SKIP_DIR_NAMES.contains(Objects.toString(part.getFileName(), ""))) {
                return true;
            }
        }
        return false;
    }

    private static SourceFileInfo parseSourceFile(Path source) throws IOException {
        String content = Files.readString(source, StandardCharsets.UTF_8);
        String stripped = stripCommentsAndLiterals(content);

        String packageName = "";
        Matcher packageMatcher = PACKAGE_PATTERN.matcher(stripped);
        if (packageMatcher.find()) {
            packageName = packageMatcher.group(1);
        }

        LinkedHashSet<String> declaredTypes = new LinkedHashSet<>();
        Matcher tokenMatcher = TOKEN_PATTERN.matcher(stripped);
        int braceDepth = 0;
        while (tokenMatcher.find()) {
            String token = tokenMatcher.group();
            if ("{".equals(token)) {
                braceDepth++;
                continue;
            }
            if ("}".equals(token)) {
                braceDepth = Math.max(0, braceDepth - 1);
                continue;
            }
            if (braceDepth == 0) {
                String typeName = tokenMatcher.group(2);
                if (typeName != null && !typeName.isBlank()) {
                    String fqcn = packageName.isBlank() ? typeName : packageName + "." + typeName;
                    declaredTypes.add(fqcn);
                }
            }
        }

        return new SourceFileInfo(source.toAbsolutePath().normalize(), packageName, List.copyOf(declaredTypes));
    }

    private static String stripCommentsAndLiterals(String source) {
        String noBlockComments = source.replaceAll("(?s)/\\*.*?\\*/", " ");
        String noLineComments = noBlockComments.replaceAll("(?m)//.*$", " ");
        String noStrings = noLineComments.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
        return noStrings.replaceAll("'(?:\\\\.|[^'\\\\])'", "''");
    }

    private static void writeCache(Path repoRoot, Path cacheDir, Map<Path, RepoFileCfg> grouped) throws IOException {
        Files.createDirectories(cacheDir);
        int written = 0;
        for (Map.Entry<Path, RepoFileCfg> entry : grouped.entrySet()) {
            Path sourcePath = entry.getKey();
            RepoFileCfg payload = entry.getValue();
            Path relativePath = repoRoot.relativize(sourcePath);
            Path outputFile = cacheDir.resolve(relativePath.toString() + ".json");
            Files.createDirectories(outputFile.getParent());
            Files.writeString(outputFile, MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8);
            written++;
        }
        log.info("Cached {} per-file CFG payloads", written);
    }

    record SourceFileInfo(Path path, String packageName, List<String> declaredTypes) {
    }

    record CliArgs(String repoRoot, String filepath, String cacheDir) {
        static CliArgs parse(String[] args) {
            String repoRoot = null;
            String filepath = null;
            String cacheDir = null;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--repo-root".equals(arg) && i + 1 < args.length) {
                    repoRoot = args[++i];
                } else if ("--filepath".equals(arg) && i + 1 < args.length) {
                    filepath = args[++i];
                } else if ("--cache-dir".equals(arg) && i + 1 < args.length) {
                    cacheDir = args[++i];
                } else {
                    throw new IllegalArgumentException("Unknown or incomplete argument: " + arg);
                }
            }

            if (repoRoot == null || repoRoot.isBlank()) {
                throw new IllegalArgumentException("--repo-root is required");
            }
            if (filepath == null && cacheDir == null) {
                throw new IllegalArgumentException("Either --filepath or --cache-dir must be provided");
            }
            return new CliArgs(repoRoot, filepath, cacheDir);
        }
    }
}

