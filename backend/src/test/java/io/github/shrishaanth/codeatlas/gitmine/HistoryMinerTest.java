package io.github.shrishaanth.codeatlas.gitmine;

import io.github.shrishaanth.codeatlas.testutil.TestRepo;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryMinerTest {

    private static final String BODY = "def f():\n    return 1\n\n\ndef g():\n    return 2\n\n\nclass K:\n    pass\n";

    @Test
    void countsCommitsAndAuthorsPerFileAndFollowsRenames(@TempDir Path dir) throws Exception {
        try (TestRepo repo = TestRepo.create(dir)) {
            repo.commit("alice@x.org", "init", Map.of("old/core.py", BODY, "util.py", "x = 1\n"));
            repo.commit("bob@x.org", "edit core", Map.of("old/core.py", BODY + "y = 2\n"));
            repo.rename("alice@x.org", "old/core.py", "pkg/core.py");
            repo.commit("Carol@X.org", "edit both", Map.of("pkg/core.py", BODY + "y = 3\n", "util.py", "x = 2\n"));
            repo.commit("alice@x.org", "add then delete", Map.of("gone.py", "z = 1\n"));
            RevCommit head = repo.commit("alice@x.org", "delete", Collections.singletonMap("gone.py", null));

            GitHistory h = new HistoryMiner(1000).mine(repo.git().getRepository(), head,
                    List.of("pkg/core.py", "util.py"), null);

            assertThat(h.commitsWalked()).isEqualTo(6);
            assertThat(h.truncated()).isFalse();
            GitHistory.FileHistory core = h.files().get("pkg/core.py");
            assertThat(core.commits()).as("init + bob's edit under the old name + rename + carol").isEqualTo(4);
            assertThat(core.authorEmails()).containsExactlyInAnyOrder("alice@x.org", "bob@x.org", "carol@x.org");
            assertThat(h.files().get("util.py").commits()).isEqualTo(2);
            assertThat(h.files()).doesNotContainKey("gone.py");
            assertThat(h.authors().get("alice@x.org").commits()).isEqualTo(4);
            assertThat(h.authors().get("carol@x.org").name()).isEqualTo("Carol");
            assertThat(h.commits().get(2).paths()).as("carol's commit, newest first")
                    .containsExactlyInAnyOrder("pkg/core.py", "util.py");
            assertThat(h.firstCommitAt()).isBefore(h.lastCommitAt());
        }
    }

    @Test
    void skipsMergeCommitsForFileCountsButCountsThemAsWalked(@TempDir Path dir) throws Exception {
        try (TestRepo repo = TestRepo.create(dir)) {
            repo.commit("a@x.org", "init", Map.of("a.py", "a = 1\n", "b.py", "b = 1\n"));
            repo.git().branchCreate().setName("feature").call();
            repo.commit("a@x.org", "main edit", Map.of("a.py", "a = 2\n"));
            repo.git().checkout().setName("feature").call();
            repo.commit("b@x.org", "feature edit", Map.of("b.py", "b = 2\n"));
            repo.git().checkout().setName("main").call();
            MergeResult merge = repo.git().merge().include(repo.git().getRepository().resolve("feature"))
                    .setMessage("merge").call();
            ObjectId headId = merge.getNewHead();

            try (RevWalk rw = new RevWalk(repo.git().getRepository())) {
                GitHistory h = new HistoryMiner(1000).mine(repo.git().getRepository(), rw.parseCommit(headId),
                        List.of("a.py", "b.py"), null);

                assertThat(h.commitsWalked()).isEqualTo(4);
                assertThat(h.commits()).hasSize(3);
                assertThat(h.files().get("b.py").commits()).as("init + feature edit, not the merge").isEqualTo(2);
            }
        }
    }

    @Test
    void stopsAtTheCommitCap(@TempDir Path dir) throws Exception {
        try (TestRepo repo = TestRepo.create(dir)) {
            RevCommit head = null;
            for (int i = 0; i < 5; i++) head = repo.commit("a@x.org", "c" + i, Map.of("a.py", "a = " + i + "\n"));

            GitHistory h = new HistoryMiner(3).mine(repo.git().getRepository(), head, List.of("a.py"), null);

            assertThat(h.commitsWalked()).isEqualTo(3);
            assertThat(h.truncated()).isTrue();
            assertThat(h.files().get("a.py").commits()).isEqualTo(3);
        }
    }
}
