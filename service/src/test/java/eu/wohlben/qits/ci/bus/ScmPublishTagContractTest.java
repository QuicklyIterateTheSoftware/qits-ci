package eu.wohlben.qits.ci.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.githost.events.SCMPublishTag;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * <b>The two strings the tag supersede is written against, checked against the record itself.</b>
 *
 * <p>{@code CiRunService.supersedeByVersion} recognises a tag event by NAME and reads its tag out of
 * a payload FIELD, and both are literals in the {@code ci} module — which depends on no other
 * context's jar, deliberately, so it cannot name {@link SCMPublishTag} at all. That is the right
 * split and it leaves exactly one thing to go wrong: qits-githost renames the event or the field,
 * this service compiles, and the supersede quietly stops firing. A multi-tag push would then build
 * every tag again, with nothing in any log to say why.
 *
 * <p>So the check lives here, in the module where the record IS on the classpath, and it is the same
 * shape as {@code EventWireReflectionTest}'s guard over the mix-in class name: a string the compiler
 * cannot check, resolved against the thing it names. A rename upstream is a red suite.
 *
 * <p>It reads the field off the <b>canonical payload</b> rather than off the record's components,
 * because the canonical form is the wire contract and the field name a consumer walks is whatever
 * {@code CanonicalJson} wrote — a component renamed only in Java would still be caught, and so
 * would one the canonical form omits.
 */
public class ScmPublishTagContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static SCMPublishTag tag(String tagName) {
    return new SCMPublishTag(
        "qits-ci",
        tagName,
        "a".repeat(40),
        "b".repeat(40),
        "A Tagger",
        "tagger@example.invalid",
        "release " + tagName,
        true,
        Instant.parse("2026-08-11T09:00:00Z"));
  }

  @Test
  public void theEventNameTheSupersedeMatchesIsTheOneThisEventRidesUnder() {
    // The signature is the simple class name — what ScmPublishCommitListener subscribes by, and
    // what the trigger engine compares a file's `event:` against.
    assertEquals(SCMPublishTag.class.getSimpleName(), CiRunService.TAG_EVENT_NAME);
  }

  @Test
  public void theFieldTheSupersedeOrdersByIsInTheCanonicalPayload() throws Exception {
    JsonNode payload = MAPPER.readTree(CanonicalJson.payload(tag("2026.811.10")));
    assertTrue(
        payload.has(CiRunService.TAG_NAME_FIELD),
        "the canonical payload carries no " + CiRunService.TAG_NAME_FIELD);
    assertEquals("2026.811.10", payload.get(CiRunService.TAG_NAME_FIELD).asText());
  }
}
