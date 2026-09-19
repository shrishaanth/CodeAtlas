package io.github.shrishaanth.codeatlas.analyze;

import io.github.shrishaanth.codeatlas.gitmine.People;
import io.github.shrishaanth.codeatlas.report.Report;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class OwnershipTest {

    private static final Instant REPO_LAST = Instant.parse("2025-06-01T00:00:00Z");

    private static final People PEOPLE = new People(List.of(
            new People.Person("a1", "Alice", List.of("alice@x.org", "alice@home.org"), List.of("Alice"), false, 50,
                    Instant.parse("2025-05-01T00:00:00Z")),
            new People.Person("a2", "Bob", List.of("bob@x.org"), List.of("Bob"), false, 10,
                    Instant.parse("2022-01-01T00:00:00Z")),
            new People.Person("a3", "dependabot[bot]", List.of("bot@x.org"), List.of("dependabot[bot]"), true, 99,
                    Instant.parse("2025-05-01T00:00:00Z"))),
            Map.of("alice@x.org", "a1", "alice@home.org", "a1", "bob@x.org", "a2", "bot@x.org", "a3"));

    private static Map<String, Report.Ownership> byPath(List<Report.Ownership> list) {
        return list.stream().collect(Collectors.toMap(Report.Ownership::path, Function.identity()));
    }

    @Test
    void mergesAliasesExcludesBotsAndComputesBusFactor() {
        Ownership.Result r = Ownership.compute(Map.of(
                "pkg/core.py", Map.of("alice@x.org", 60, "alice@home.org", 30, "bob@x.org", 10, "bot@x.org", 500),
                "pkg/util.py", Map.of("bob@x.org", 150),
                "pkg/sub/tiny.py", Map.of("bob@x.org", 20),
                "only_bot.py", Map.of("bot@x.org", 40)), PEOPLE, REPO_LAST);

        Map<String, Report.Ownership> files = byPath(r.files());
        assertThat(files).doesNotContainKey("only_bot.py");

        Report.Ownership core = files.get("pkg/core.py");
        assertThat(core.totalLines()).as("bot lines excluded").isEqualTo(100);
        assertThat(core.owners()).containsExactly(new Report.Owner("a1", 90, 0.9), new Report.Owner("a2", 10, 0.1));
        assertThat(core.busFactor()).isEqualTo(1);
        assertThat(core.topOwnerActive()).isTrue();
        assertThat(core.flags()).containsExactly("single-owner");

        Report.Ownership util = files.get("pkg/util.py");
        assertThat(util.topOwnerActive()).as("Bob's last commit is 3+ years before the repo's").isFalse();
        assertThat(util.flags()).containsExactly("single-owner", "orphaned");
        assertThat(files.get("pkg/sub/tiny.py").flags()).as("under 100 lines: no flags").isEmpty();

        Map<String, Report.Ownership> dirs = byPath(r.directories());
        assertThat(dirs).containsOnlyKeys(".", "pkg", "pkg/sub");
        Report.Ownership pkg = dirs.get("pkg");
        assertThat(pkg.totalLines()).isEqualTo(270);
        assertThat(pkg.owners()).containsExactly(new Report.Owner("a2", 180, 0.667), new Report.Owner("a1", 90, 0.333));
        assertThat(pkg.busFactor()).isEqualTo(1);
    }

    @Test
    void busFactorCountsPeopleNeededForHalfTheLines() {
        Map<String, People.Person> byId = PEOPLE.people().stream()
                .collect(Collectors.toMap(People.Person::id, Function.identity()));

        assertThat(Ownership.summarize("f", Map.of("a1", 40, "a2", 35, "a3", 25), byId, REPO_LAST).busFactor()).isEqualTo(2);
        assertThat(Ownership.summarize("f", Map.of("a1", 50, "a2", 50), byId, REPO_LAST).busFactor()).isEqualTo(1);
    }

    @Test
    void listsAtMostFiveOwnersAndCountsTheRest() {
        People many = new People(
                java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(i -> new People.Person("a" + i, "P" + i,
                        List.of("p" + i + "@x.org"), List.of(), false, 1, REPO_LAST)).toList(),
                java.util.stream.IntStream.rangeClosed(1, 7).boxed()
                        .collect(Collectors.toMap(i -> "p" + i + "@x.org", i -> "a" + i)));
        Map<String, Integer> lines = java.util.stream.IntStream.rangeClosed(1, 7).boxed()
                .collect(Collectors.toMap(i -> "p" + i + "@x.org", i -> 10));

        Report.Ownership o = Ownership.compute(Map.of("f.py", lines), many, REPO_LAST).files().get(0);

        assertThat(o.owners()).hasSize(5);
        assertThat(o.otherLines()).isEqualTo(20);
        assertThat(o.ownerCount()).isEqualTo(7);
    }

    @Test
    void ancestorsIncludeTheRoot() {
        assertThat(Ownership.ancestors("a/b/c.py")).containsExactly(".", "a", "a/b");
        assertThat(Ownership.ancestors("c.py")).containsExactly(".");
    }
}
