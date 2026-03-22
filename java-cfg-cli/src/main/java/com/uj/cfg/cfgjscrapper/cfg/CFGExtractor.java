package com.uj.cfg.cfgjscrapper.cfg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.Body;
import soot.G;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.options.Options;
import soot.toolkits.graph.Block;
import soot.toolkits.graph.ExceptionalBlockGraph;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

public class CFGExtractor {

    private final Logger log = LoggerFactory.getLogger(CFGExtractor.class);
    private final Object sootLock = new Object();

    public List<ClassCfg> extractFromClasses(Path classesDir) throws IOException {
        if (!Files.exists(classesDir)) {
            throw new IOException("Classes directory does not exist: " + classesDir);
        }
        Set<String> classDirs = findPureClassDirs(classesDir);
        if (classDirs.isEmpty()) {
            log.warn("No .class files found under {}", classesDir);
            return Collections.emptyList();
        }

        List<SootClass> classes;
        Map<String, String> fqcnToPath = new HashMap<>();

        synchronized (sootLock) {
            configureSoot(classDirs);
            List<SootClass> loaded = new ArrayList<>();
            for (String dir : classDirs) {
                Path root = Path.of(dir);
                buildUpSootClasses(classesDir, dir, root, loaded, fqcnToPath);
            }

            try {
                Scene.v().loadNecessaryClasses();
            } catch (Throwable t) {
                log.warn("Scene.v().loadNecessaryClasses() failed: {}", t.toString());
            }

            if (loaded.isEmpty()) {
                log.warn("No classes were explicitly loaded from classDirs; aborting CFG extraction");
                return Collections.emptyList();
            }
            classes = new ArrayList<>(loaded);
        }

        List<SootClass> filtered = new ArrayList<>();
        for (SootClass sc : classes) {
            try {
                SootClass sup = sc.getSuperclass();
                if (sup == null && !"java.lang.Object".equals(sc.getName())) {
                    continue;
                }
            } catch (Throwable t) {
                continue;
            }
            filtered.add(sc);
        }
        classes = filtered;

        List<ClassCfg> result = new ArrayList<>();
        for (SootClass sc : classes) {
            try {
                List<MethodCfg> methodCfgs = new ArrayList<>();
                for (SootMethod method : sc.getMethods()) {
                    if (!method.isConcrete()) {
                        continue;
                    }
                    try {
                        Body body = method.hasActiveBody() ? method.getActiveBody() : method.retrieveActiveBody();
                        ExceptionalBlockGraph ebg = new ExceptionalBlockGraph(body);
                        Map<String, String[]> graph = buildGraph(ebg);
                        if (graph.size() <= 1) {
                            continue;
                        }

                        List<Integer> outDegrees = new ArrayList<>();
                        for (String node : graph.keySet()) {
                            outDegrees.add(graph.get(node) == null ? 0 : graph.get(node).length);
                        }
                        Collections.sort(outDegrees);

                        Map<String, Integer> inDeg = new LinkedHashMap<>();
                        for (String node : graph.keySet()) {
                            inDeg.put(node, 0);
                        }
                        for (String[] targets : graph.values()) {
                            if (targets == null) {
                                continue;
                            }
                            for (String t : targets) {
                                inDeg.put(t, inDeg.getOrDefault(t, 0) + 1);
                            }
                        }
                        List<Integer> inDegrees = new ArrayList<>();
                        for (String node : inDeg.keySet()) {
                            inDegrees.add(inDeg.get(node));
                        }
                        Collections.sort(inDegrees);

                        String name = sc.getName() + "." + method.getName() + method.getSubSignature();
                        methodCfgs.add(new MethodCfg(name, graph, inDegrees.toString(), outDegrees.toString(), null));
                    } catch (Throwable t) {
                        log.debug("Failed to process method {} in class {}: {}", method.getName(), sc.getName(), t.toString());
                    }
                }

                if (!methodCfgs.isEmpty()) {
                    String filepath = fqcnToPath.getOrDefault(sc.getName(), sc.getName().replace('.', '/') + ".class");
                    result.add(new ClassCfg(filepath, sc.getName(), methodCfgs));
                }
            } catch (Throwable t) {
                log.debug("Failed to process class {}: {}", sc.getName(), t.toString());
            }
        }

        return result;
    }

    private void buildUpSootClasses(Path classesDir, String dir, Path root, List<SootClass> loaded, Map<String, String> fqcnToPath) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .forEach(p -> {
                        try {
                            Path rel = root.relativize(p);
                            String fqcn = rel.toString().replace(File.separatorChar, '.');
                            if (fqcn.endsWith(".class")) {
                                fqcn = fqcn.substring(0, fqcn.length() - 6);
                            }
                            if (fqcn.isEmpty() || fqcn.startsWith("META-INF")) {
                                return;
                            }
                            SootClass sc = Scene.v().loadClassAndSupport(fqcn);
                            sc.setApplicationClass();
                            loaded.add(sc);
                            String relPath = rel.toString().replace(File.separatorChar, '/');
                            fqcnToPath.put(fqcn, relPath);
                        } catch (Throwable ex) {
                            log.debug("Failed loading class {} from {}", p, dir, ex);
                        }
                    });
        } catch (IOException ignored) {
            log.error("Failed to walk class files under {}", classesDir, ignored);
        }
    }

    private void configureSoot(Set<String> classDirs) {
        G.reset();

        Options.v().set_prepend_classpath(true);
        Options.v().set_process_dir(new ArrayList<>(classDirs));
        Options.v().set_allow_phantom_refs(true);
        Options.v().set_output_format(Options.output_format_none);
        Options.v().set_whole_program(false);
        Options.v().set_src_prec(Options.src_prec_class);
        Options.v().set_keep_line_number(true);

        String sep = File.pathSeparator;
        StringBuilder cp = new StringBuilder();
        for (String d : classDirs) {
            if (cp.length() > 0) {
                cp.append(sep);
            }
            cp.append(d);
        }

        String javaClassPath = System.getProperty("java.class.path", "");
        if (!javaClassPath.isEmpty()) {
            if (cp.length() > 0) {
                cp.append(sep);
            }
            cp.append(javaClassPath);
        }

        final int jarLimit = 256;
        Set<String> addedJars = new HashSet<>();
        for (String d : classDirs) {
            try {
                Path pd = Path.of(d);
                Path moduleRoot = pd;
                for (int up = 0; up < 4 && moduleRoot.getParent() != null; up++) {
                    moduleRoot = moduleRoot.getParent();
                }
                try (Stream<Path> walk = Files.find(moduleRoot, 4, (p, attr) -> attr.isRegularFile() && p.toString().endsWith(".jar"))) {
                    Iterator<Path> it = walk.iterator();
                    while (it.hasNext() && addedJars.size() < jarLimit) {
                        String abs = it.next().toAbsolutePath().toString();
                        if (addedJars.add(abs)) {
                            cp.append(sep).append(abs);
                        }
                    }
                }
            } catch (Exception ignored) {
                log.error("Failed to find jars under {}", d, ignored);
            }
            if (addedJars.size() >= jarLimit) {
                break;
            }
        }

        cp.append(sep).append("jrt:");
        Options.v().set_soot_classpath(cp.toString());

        try {
            Scene.v().addBasicClass("java.lang.Object", SootClass.BODIES);
            Scene.v().addBasicClass("java.lang.Throwable", SootClass.BODIES);
            Scene.v().addBasicClass("java.lang.String", SootClass.BODIES);
            Scene.v().addBasicClass("java.lang.Class", SootClass.BODIES);
        } catch (Exception ignored) {
            log.error("Failed to configure soot: ", ignored);
        }
    }

    private Map<String, String[]> buildGraph(ExceptionalBlockGraph blockGraph) {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        Map<Block, String> ids = new HashMap<>();

        int i = 0;
        for (Block b : blockGraph) {
            String id = "B" + i++;
            ids.put(b, id);
            graph.put(id, new LinkedHashSet<>());
        }

        for (Block b : blockGraph) {
            String from = ids.get(b);
            for (Block succ : blockGraph.getSuccsOf(b)) {
                String to = ids.get(succ);
                if (to != null) {
                    graph.get(from).add(to);
                }
            }
        }

        Map<String, String[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : graph.entrySet()) {
            out.put(e.getKey(), e.getValue().toArray(new String[0]));
        }

        return out;
    }

    private Set<String> findPureClassDirs(Path root) throws IOException {
        Set<String> parents = new LinkedHashSet<>();

        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .map(Path::getParent)
                    .filter(Objects::nonNull)
                    .map(p -> p.toAbsolutePath().normalize().toString())
                    .distinct()
                    .forEach(parents::add);
        }

        Set<String> roots = new LinkedHashSet<>();
        List<String> topPkgs = List.of("com", "org", "net", "io", "javax", "jakarta");

        for (String p : parents) {
            Path pd = Path.of(p);
            Path cur = pd;
            while (cur != null) {
                String fn = cur.getFileName() == null ? null : cur.getFileName().toString();
                if ("classes".equals(fn) || "test-classes".equals(fn)) {
                    break;
                }
                cur = cur.getParent();
            }
            if (cur != null && Files.isDirectory(cur)) {
                roots.add(cur.toAbsolutePath().normalize().toString());
                continue;
            }

            Path cand = pd;
            boolean found = false;
            while (cand != null && cand.startsWith(root)) {
                try (Stream<Path> children = Files.list(cand)) {
                    for (Path child : (Iterable<Path>) children::iterator) {
                        String name = child.getFileName() == null ? "" : child.getFileName().toString();
                        if (topPkgs.contains(name)) {
                            roots.add(cand.toAbsolutePath().normalize().toString());
                            found = true;
                            break;
                        }
                    }
                } catch (IOException ignored) {

                }
                if (found) {
                    break;
                }
                cand = cand.getParent();
            }

            if (!found) {
                roots.add(pd.toAbsolutePath().normalize().toString());
            }
        }

        return roots;
    }
}

