package io.github.shrishaanth.codeatlas.analyze;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Directory-level dependencies from file imports: layers and import cycles.
 * Definitions are in docs/metrics.md, "Layers and import cycles".
 */
public final class Layers {

    /** One directory-to-directory dependency with a file import that shows it. */
    public record Dependency(String from, String to, String exampleFrom, String exampleTo) {
    }

    /** @param dirs directories in the cycle, sorted; {@code dependencies} are the edges inside it */
    public record Cycle(List<String> dirs, List<Dependency> dependencies) {
    }

    /** @param layers directory to layer (0 = depends on no other directory) */
    public record Result(Map<String, Integer> layers, List<Cycle> cycles) {
    }

    private Layers() {
    }

    /**
     * @param graph   import graph over non-test files
     * @param dirOf   maps a file to its directory (component)
     */
    public static Result compute(ImportGraph graph, Function<String, String> dirOf) {
        // Directory graph with one example import per edge. Imports made by __init__.py are skipped:
        // packages re-export their submodules, which would make nearly every package look circular.
        Map<String, Map<String, Dependency>> deps = new TreeMap<>();
        for (String file : graph.nodes()) {
            deps.computeIfAbsent(dirOf.apply(file), k -> new TreeMap<>());
            if (isInit(file)) continue;
            for (String target : new TreeSet<>(graph.importsOf(file))) {
                String a = dirOf.apply(file), b = dirOf.apply(target);
                if (a.equals(b)) continue;
                deps.get(a).putIfAbsent(b, new Dependency(a, b, file, target));
            }
        }
        deps.values().forEach(m -> m.keySet().forEach(t -> deps.computeIfAbsent(t, k -> new TreeMap<>())));

        List<List<String>> sccs = stronglyConnected(deps.keySet(), d -> deps.get(d).keySet());
        Map<String, Integer> sccOf = new HashMap<>();
        for (int i = 0; i < sccs.size(); i++) for (String d : sccs.get(i)) sccOf.put(d, i);

        // Tarjan emits components in reverse topological order: dependencies come first.
        int[] sccLayer = new int[sccs.size()];
        for (int i = 0; i < sccs.size(); i++) {
            int layer = 0;
            for (String d : sccs.get(i)) {
                for (String t : deps.get(d).keySet()) {
                    int j = sccOf.get(t);
                    if (j != i) layer = Math.max(layer, sccLayer[j] + 1);
                }
            }
            sccLayer[i] = layer;
        }

        Map<String, Integer> layers = new TreeMap<>();
        sccOf.forEach((d, i) -> layers.put(d, sccLayer[i]));

        List<Cycle> cycles = new ArrayList<>();
        for (List<String> scc : sccs) {
            if (scc.size() < 2) continue;
            List<String> dirs = scc.stream().sorted().toList();
            List<Dependency> inside = new ArrayList<>();
            for (String d : dirs) {
                deps.get(d).forEach((t, dep) -> {
                    if (scc.contains(t)) inside.add(dep);
                });
            }
            cycles.add(new Cycle(dirs, inside));
        }
        cycles.sort((x, y) -> y.dirs().size() != x.dirs().size() ? y.dirs().size() - x.dirs().size()
                : x.dirs().get(0).compareTo(y.dirs().get(0)));
        return new Result(layers, cycles);
    }

    static boolean isInit(String path) {
        return path.equals("__init__.py") || path.endsWith("/__init__.py");
    }

    /** Iterative Tarjan: components are returned dependencies-first (reverse topological order). */
    static List<List<String>> stronglyConnected(Collection<String> nodes, Function<String, Collection<String>> next) {
        Map<String, Integer> index = new HashMap<>(), low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        java.util.Set<String> onStack = new java.util.HashSet<>();
        List<List<String>> out = new ArrayList<>();
        int[] counter = {0};

        for (String start : nodes) {
            if (index.containsKey(start)) continue;
            Deque<Object[]> work = new ArrayDeque<>();
            work.push(new Object[]{start, next.apply(start).iterator()});
            index.put(start, counter[0]);
            low.put(start, counter[0]++);
            stack.push(start);
            onStack.add(start);
            while (!work.isEmpty()) {
                Object[] frame = work.peek();
                String v = (String) frame[0];
                @SuppressWarnings("unchecked")
                java.util.Iterator<String> it = (java.util.Iterator<String>) frame[1];
                if (it.hasNext()) {
                    String w = it.next();
                    if (!index.containsKey(w)) {
                        index.put(w, counter[0]);
                        low.put(w, counter[0]++);
                        stack.push(w);
                        onStack.add(w);
                        work.push(new Object[]{w, next.apply(w).iterator()});
                    } else if (onStack.contains(w)) {
                        low.put(v, Math.min(low.get(v), index.get(w)));
                    }
                } else {
                    work.pop();
                    if (!work.isEmpty()) {
                        String parent = (String) work.peek()[0];
                        low.put(parent, Math.min(low.get(parent), low.get(v)));
                    }
                    if (low.get(v).equals(index.get(v))) {
                        List<String> comp = new ArrayList<>();
                        String w;
                        do {
                            w = stack.pop();
                            onStack.remove(w);
                            comp.add(w);
                        } while (!w.equals(v));
                        out.add(comp);
                    }
                }
            }
        }
        return out;
    }
}
