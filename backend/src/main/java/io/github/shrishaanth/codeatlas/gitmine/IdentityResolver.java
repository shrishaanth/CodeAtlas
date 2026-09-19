package io.github.shrishaanth.codeatlas.gitmine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Merges raw identities into people. Rules are in docs/metrics.md, "People: merging identities". */
public final class IdentityResolver {

    private static final Pattern BOT = Pattern.compile(
            "\\[bot]|^(dependabot|renovate|pre-commit-ci|github-actions|greenkeeper|allcontributors|snyk-bot)\\b",
            Pattern.CASE_INSENSITIVE);

    private IdentityResolver() {
    }

    public static People resolve(Collection<GitHistory.Author> identities, Mailmap mailmap) {
        UnionFind uf = new UnionFind();
        Map<String, Instant> lastAt = new HashMap<>();
        Map<String, String> latestName = new HashMap<>();
        Map<String, Set<String>> names = new HashMap<>();
        Map<String, Integer> commits = new HashMap<>();
        Map<String, List<String>> emailsByFullName = new HashMap<>();

        for (GitHistory.Author a : identities) {
            String email = a.email().toLowerCase(Locale.ROOT);
            uf.add(email);
            commits.merge(email, a.commits(), Integer::sum);
            lastAt.merge(email, a.lastCommitAt(), (x, y) -> x.isAfter(y) ? x : y);
            latestName.put(email, mailmap.map(a.name(), email).name());
            for (String rawName : a.names()) {
                Mailmap.Identity mapped = mailmap.map(rawName, email);
                names.computeIfAbsent(email, k -> new TreeSet<>()).add(mapped.name());
                // Rule 1: .mailmap sends this identity to another email.
                if (!mapped.email().equals(email)) {
                    uf.add(mapped.email());
                    uf.union(email, mapped.email());
                }
                // Rule 3: same full name (two or more words).
                String key = normalizeName(mapped.name());
                if (key.contains(" ")) emailsByFullName.computeIfAbsent(key, k -> new ArrayList<>()).add(email);
            }
        }
        for (List<String> sameName : emailsByFullName.values()) {
            for (int i = 1; i < sameName.size(); i++) uf.union(sameName.get(0), sameName.get(i));
        }
        // Rule 2 (same email) holds by construction: emails are already lower-cased keys.

        Map<String, Group> groups = new LinkedHashMap<>();
        for (String email : uf.members()) {
            Group g = groups.computeIfAbsent(uf.find(email), k -> new Group());
            if (!commits.containsKey(email)) continue; // a mailmap target that never committed itself
            g.emails.add(email);
            g.commits += commits.get(email);
            g.names.addAll(names.getOrDefault(email, Set.of()));
            Instant t = lastAt.get(email);
            if (g.last == null || t.isAfter(g.last)) {
                g.last = t;
                g.name = latestName.get(email);
            }
        }

        List<Group> sorted = groups.values().stream()
                .filter(g -> !g.emails.isEmpty())
                .sorted(Comparator.comparingInt((Group g) -> g.commits).reversed()
                        .thenComparing(g -> g.name == null ? "" : g.name)
                        .thenComparing(g -> g.emails.first()))
                .toList();
        List<People.Person> people = new ArrayList<>();
        Map<String, String> byEmail = new HashMap<>();
        for (Group g : sorted) {
            String id = "a" + (people.size() + 1);
            boolean bot = g.names.stream().anyMatch(n -> BOT.matcher(n).find())
                    || g.emails.stream().anyMatch(e -> BOT.matcher(e).find());
            people.add(new People.Person(id, g.name, List.copyOf(g.emails), List.copyOf(g.names), bot, g.commits, g.last));
            g.emails.forEach(e -> byEmail.put(e, id));
        }
        return new People(List.copyOf(people), Map.copyOf(byEmail));
    }

    static String normalizeName(String name) {
        return name == null ? "" : name.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static final class Group {
        final TreeSet<String> emails = new TreeSet<>();
        final Set<String> names = new TreeSet<>();
        String name;
        Instant last;
        int commits;
    }

    private static final class UnionFind {
        private final Map<String, String> parent = new LinkedHashMap<>();

        void add(String x) {
            parent.putIfAbsent(x, x);
        }

        String find(String x) {
            String root = x;
            while (!parent.get(root).equals(root)) root = parent.get(root);
            while (!parent.get(x).equals(root)) {
                String next = parent.get(x);
                parent.put(x, root);
                x = next;
            }
            return root;
        }

        void union(String a, String b) {
            String ra = find(a), rb = find(b);
            if (!ra.equals(rb)) parent.put(rb, ra);
        }

        Collection<String> members() {
            return List.copyOf(parent.keySet());
        }
    }
}
