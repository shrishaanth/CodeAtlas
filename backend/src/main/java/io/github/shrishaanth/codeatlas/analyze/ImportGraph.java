package io.github.shrishaanth.codeatlas.analyze;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Directed graph of resolved imports between repository files: an edge points from importer to imported. */
public class ImportGraph {

    private final Set<String> nodes = new TreeSet<>();
    private final Map<String, Set<String>> out = new HashMap<>();
    private final Map<String, Set<String>> in = new HashMap<>();

    public void addNode(String path) {
        nodes.add(path);
    }

    /** Adds an edge; self-imports and duplicates are ignored. */
    public void addEdge(String from, String to) {
        if (from.equals(to)) return;
        nodes.add(from);
        nodes.add(to);
        if (out.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to)) {
            in.computeIfAbsent(to, k -> new LinkedHashSet<>()).add(from);
        }
    }

    public Set<String> nodes() {
        return Collections.unmodifiableSet(nodes);
    }

    public Set<String> importsOf(String path) {
        return out.getOrDefault(path, Set.of());
    }

    public Set<String> importersOf(String path) {
        return in.getOrDefault(path, Set.of());
    }

    public int edgeCount() {
        return out.values().stream().mapToInt(Set::size).sum();
    }

    /** The subgraph on the given nodes (edges with both ends kept). */
    public ImportGraph restrictTo(Collection<String> keep) {
        Set<String> k = new TreeSet<>(keep);
        ImportGraph g = new ImportGraph();
        for (String n : k) {
            if (nodes.contains(n)) g.addNode(n);
        }
        for (String from : k) {
            for (String to : importsOf(from)) {
                if (k.contains(to)) g.addEdge(from, to);
            }
        }
        return g;
    }

    /**
     * PageRank with edges from importer to imported, so rank flows to the files being depended on.
     * Rank from files that import nothing (dangling nodes) is spread evenly over all nodes.
     */
    public Map<String, Double> pageRank(double damping, int iterations) {
        List<String> ids = List.copyOf(nodes);
        int n = ids.size();
        Map<String, Double> result = new HashMap<>();
        if (n == 0) return result;

        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) index.put(ids.get(i), i);
        int[][] outIdx = new int[n][];
        for (int i = 0; i < n; i++) {
            outIdx[i] = importsOf(ids.get(i)).stream().mapToInt(index::get).toArray();
        }

        double[] rank = new double[n];
        java.util.Arrays.fill(rank, 1.0 / n);
        for (int iter = 0; iter < iterations; iter++) {
            double[] next = new double[n];
            double dangling = 0;
            for (int i = 0; i < n; i++) {
                if (outIdx[i].length == 0) {
                    dangling += rank[i];
                } else {
                    double share = rank[i] / outIdx[i].length;
                    for (int j : outIdx[i]) next[j] += share;
                }
            }
            double base = (1 - damping) / n + damping * dangling / n;
            for (int i = 0; i < n; i++) next[i] = base + damping * next[i];
            rank = next;
        }
        for (int i = 0; i < n; i++) result.put(ids.get(i), rank[i]);
        return result;
    }
}
