package io.github.shrishaanth.codeatlas.gitmine;

import io.github.shrishaanth.codeatlas.testutil.TestRepo;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BlameMinerTest {

    private static final String ORIGINAL = """
            def f(x):
                if x:
                    return "a"
                return "b"
            """;

    @Test
    void whitespaceOnlyChangesDoNotMoveOwnership(@TempDir Path dir) throws Exception {
        try (TestRepo repo = TestRepo.create(dir)) {
            repo.commit("alice@x.org", "add", Map.of("m.py", ORIGINAL));
            // Bob wraps everything in a class: every old line is re-indented, one line is new.
            String reindented = "class K:\n" + ORIGINAL.indent(4);
            RevCommit head = repo.commit("bob@x.org", "wrap in class", Map.of("m.py", reindented));

            BlameMiner.Result r = new BlameMiner(2).mine(repo.git().getRepository(), head, List.of("m.py"), null);

            assertThat(r.lines().get("m.py")).containsExactlyInAnyOrderEntriesOf(Map.of("alice@x.org", 4, "bob@x.org", 1));
            assertThat(r.identities()).containsKeys("alice@x.org", "bob@x.org");
            assertThat(r.failed()).isEmpty();
        }
    }

    @Test
    void skipsCommitsListedInIgnoreRevs(@TempDir Path dir) throws Exception {
        try (TestRepo repo = TestRepo.create(dir)) {
            repo.commit("alice@x.org", "add", Map.of("m.py", ORIGINAL));
            RevCommit reformat = repo.commit("carol@x.org", "reformat quotes",
                    Map.of("m.py", ORIGINAL.replace('"', '\'')));

            RevCommit before = repo.commit("dave@x.org", "unrelated", Map.of("other.py", "x = 1\n"));
            BlameMiner.Result without = new BlameMiner(1).mine(repo.git().getRepository(), before, List.of("m.py"), null);
            RevCommit head = repo.commit("dave@x.org", "ignore the reformat", Map.of(
                    BlameMiner.IGNORE_REVS_FILE, "# bulk reformat\n" + reformat.getName() + "\n"
                            + "0000000000000000000000000000000000000000  # not in this repo\n"));
            BlameMiner.Result with = new BlameMiner(1).mine(repo.git().getRepository(), head, List.of("m.py"), null);

            assertThat(without.lines().get("m.py")).containsEntry("carol@x.org", 2);
            assertThat(with.lines().get("m.py")).containsExactlyEntriesOf(Map.of("alice@x.org", 4));
            assertThat(with.ignoredRevisions()).containsExactly(reformat.getName());
        }
    }

    @Test
    void parallelBlameMatchesSingleThreaded(@TempDir Path dir) throws Exception {
        try (TestRepo repo = TestRepo.create(dir)) {
            repo.commit("alice@x.org", "add", Map.of("a.py", "a = 1\nb = 2\n", "b.py", "c = 3\n", "c.py", "d = 4\n"));
            RevCommit head = repo.commit("bob@x.org", "edit", Map.of("a.py", "a = 1\nb = 5\n", "c.py", "d = 4\ne = 5\n"));
            List<String> paths = List.of("a.py", "b.py", "c.py");

            BlameMiner.Result one = new BlameMiner(1).mine(repo.git().getRepository(), head, paths, null);
            BlameMiner.Result many = new BlameMiner(4).mine(repo.git().getRepository(), head, paths, null);

            assertThat(many.lines()).isEqualTo(one.lines());
            assertThat(one.lines().get("a.py")).isEqualTo(Map.of("alice@x.org", 1, "bob@x.org", 1));
        }
    }
}
