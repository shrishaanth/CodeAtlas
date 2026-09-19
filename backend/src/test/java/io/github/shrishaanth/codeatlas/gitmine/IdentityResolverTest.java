package io.github.shrishaanth.codeatlas.gitmine;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityResolverTest {

    private static GitHistory.Author id(String email, String name, int commits, String lastAt, String... otherNames) {
        Set<String> names = new java.util.TreeSet<>(List.of(otherNames));
        names.add(name);
        return new GitHistory.Author(email, name, names, commits, Instant.parse(lastAt));
    }

    @Test
    void mergesSameFullNameAcrossEmailsButNotSingleNames() {
        People p = IdentityResolver.resolve(List.of(
                id("jane@work.com", "Jane Doe", 30, "2024-01-01T00:00:00Z"),
                id("12345+jane@users.noreply.github.com", "jane  doe", 5, "2025-06-01T00:00:00Z"),
                id("david@a.org", "david", 3, "2024-01-01T00:00:00Z"),
                id("david@b.org", "David", 2, "2024-01-01T00:00:00Z")), Mailmap.EMPTY);

        assertThat(p.people()).hasSize(3);
        People.Person jane = p.people().get(0);
        assertThat(jane.id()).isEqualTo("a1");
        assertThat(jane.commits()).isEqualTo(35);
        assertThat(jane.emails()).containsExactly("12345+jane@users.noreply.github.com", "jane@work.com");
        assertThat(jane.name()).as("name from the most recent commit").isEqualTo("jane  doe");
        assertThat(p.idForEmail("JANE@work.com")).contains("a1");
        assertThat(p.idForEmail("david@a.org")).isNotEqualTo(p.idForEmail("david@b.org"));
    }

    @Test
    void appliesMailmapIncludingNameSpecificEntries() {
        Mailmap mailmap = Mailmap.parse("""
                # comment
                Jane Doe <jane@work.com> <jane@old.com>
                <jane@work.com> <JANE@Laptop.local>
                Proper Bob <bob@x.org> Bobby <shared@ci.org>
                Renamed Only <carol@x.org>
                """);

        People p = IdentityResolver.resolve(List.of(
                id("jane@work.com", "Jane Doe", 10, "2024-01-01T00:00:00Z"),
                id("jane@old.com", "J", 4, "2020-01-01T00:00:00Z"),
                id("jane@laptop.local", "jane", 2, "2021-01-01T00:00:00Z"),
                id("shared@ci.org", "Bobby", 3, "2022-01-01T00:00:00Z", "Someone Else"),
                id("carol@x.org", "carol", 1, "2022-01-01T00:00:00Z")), mailmap);

        People.Person jane = p.byId(p.idForEmail("jane@old.com").orElseThrow()).orElseThrow();
        assertThat(jane.emails()).containsExactly("jane@laptop.local", "jane@old.com", "jane@work.com");
        assertThat(jane.commits()).isEqualTo(16);
        // The unit is an email: a name-specific entry maps the whole email (documented limitation).
        People.Person bob = p.byId(p.idForEmail("shared@ci.org").orElseThrow()).orElseThrow();
        assertThat(bob.name()).isEqualTo("Proper Bob");
        assertThat(bob.emails()).containsExactly("shared@ci.org");
        assertThat(p.byId(p.idForEmail("carol@x.org").orElseThrow()).orElseThrow().name()).isEqualTo("Renamed Only");
    }

    @Test
    void mailmapNameSpecificEntryOnlyMatchesThatName() {
        Mailmap mailmap = Mailmap.parse("Proper Bob <bob@x.org> Bobby <shared@ci.org>");

        assertThat(mailmap.map("Bobby", "shared@ci.org")).isEqualTo(new Mailmap.Identity("Proper Bob", "bob@x.org"));
        assertThat(mailmap.map("Other", "shared@ci.org")).isEqualTo(new Mailmap.Identity("Other", "shared@ci.org"));
    }

    @Test
    void comparesNamesAfterUnicodeNormalization() {
        // Seen in Flask's history: the same "ä" as one code point and as "a" + combining diaeresis.
        People p = IdentityResolver.resolve(List.of(
                id("dan@a.org", "Daniel Neuhäuser", 5, "2020-01-01T00:00:00Z"),
                id("dan@b.org", "Daniel Neuhäuser", 3, "2021-01-01T00:00:00Z")), Mailmap.EMPTY);

        assertThat(p.people()).hasSize(1);
    }

    @Test
    void flagsBots() {
        People p = IdentityResolver.resolve(List.of(
                id("49699333+dependabot[bot]@users.noreply.github.com", "dependabot[bot]", 50, "2024-01-01T00:00:00Z"),
                id("66853113+pre-commit-ci[bot]@users.noreply.github.com", "pre-commit-ci[bot]", 20, "2024-01-01T00:00:00Z"),
                id("dev@x.org", "Robot Framework Fan", 1, "2024-01-01T00:00:00Z")), Mailmap.EMPTY);

        assertThat(p.people()).extracting(People.Person::bot).containsExactly(true, true, false);
    }
}
