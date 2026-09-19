package io.github.shrishaanth.codeatlas.gitmine;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Raw git identities merged into people. Rules are in docs/metrics.md, "People: merging identities".
 *
 * @param people  ordered by commits, most first; ids are {@code a1, a2, ...} in that order
 * @param byEmail lower-cased raw email to person id
 */
public record People(List<Person> people, Map<String, String> byEmail) {

    /**
     * @param emails every raw email merged into this person, sorted
     * @param names  every name seen, sorted
     */
    public record Person(String id, String name, List<String> emails, List<String> names, boolean bot,
                         int commits, Instant lastCommitAt) {
    }

    public Optional<String> idForEmail(String email) {
        return Optional.ofNullable(byEmail.get(email.toLowerCase(Locale.ROOT)));
    }

    public Optional<Person> byId(String id) {
        return people.stream().filter(p -> p.id().equals(id)).findFirst();
    }
}
