package eu.wohlben.qits.ci.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Replaces {@link CiDockerRunner} for the service suite (ci's own fake-runner seam — the {@code
 * FakeContainerRuntime} duplication pattern: the ci module's test copy is invisible to this
 * module). Unlike ci's scripted fake, this one is <b>honest</b>: it performs the real step
 * semantics — clone the repo at the pushed sha from the real in-process git host, then {@code bash
 * -c <script>} with the checkout as CWD — just as host processes instead of a container, so the
 * suite stays docker-free.
 */
@Mock
@ApplicationScoped
public class FakeCiStepRunner implements CiStepRunner {

  @ConfigProperty(name = "qits.ci.git-host-url")
  String gitHostUrl;

  @Override
  public StepResult run(StepSpec spec) {
    Path work = null;
    try {
      work = Files.createTempDirectory("ci-fake-step");
      Path workspace = work.resolve("workspace");
      String cloneUrl = gitHostUrl.replaceAll("/+$", "") + "/git/" + spec.repoId();
      // The prelude, exactly as the real runner frames it: a failure here means /workspace was
      // never produced (workspaceReady=false), so the exit code belongs to git, not the pipeline.
      Result clone = exec(work, List.of("git", "clone", "-q", cloneUrl, workspace.toString()));
      if (clone.exitCode() != 0) {
        return new StepResult(clone.exitCode(), clone.output(), false, false);
      }
      Result checkout = exec(workspace, List.of("git", "checkout", "-q", spec.sha()));
      if (checkout.exitCode() != 0) {
        return new StepResult(checkout.exitCode(), checkout.output(), false, false);
      }
      Result script = exec(workspace, List.of("bash", "-c", spec.script()));
      return new StepResult(script.exitCode(), script.output(), false, true);
    } catch (Exception e) {
      return new StepResult(-1, e.toString(), false, false);
    } finally {
      deleteQuietly(work);
    }
  }

  private record Result(int exitCode, String output) {}

  private static Result exec(Path cwd, List<String> command) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(cwd.toFile());
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String output = new String(p.getInputStream().readAllBytes());
    return new Result(p.waitFor(), output);
  }

  private static void deleteQuietly(Path root) {
    if (root == null) {
      return;
    }
    try (var walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (Exception ignored) {
      // best-effort temp cleanup
    }
  }
}
