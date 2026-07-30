package eu.wohlben.qits.ci.api;

import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

/**
 * {@code /ci} → {@code /ci/}, and nothing else.
 *
 * <p>Quinoa mounts the web client at {@code /ci/*}, which does not match the bare segment — so
 * before this route existed, typing {@code /ci} into a browser answered 404 while {@code /ci/}
 * served the client. Upstream behaviour, but not a defensible surface: the segment is this
 * service's to serve in every spelling, and the bare one means "take me to the client".
 *
 * <p>A raw Vert.x route because the bare segment belongs to no other machinery: JAX-RS lives under
 * {@code /ci/api} and Quinoa deliberately does not match here. GET and HEAD only — the bare
 * segment has no meaning for a write, and a machine client POSTing to {@code /ci} should keep its
 * 404 rather than be bounced at HTML. 301, because the answer will never be anything else, and the
 * query string travels: a deep link that lost its slash keeps its parameters.
 */
@Singleton
public class WebUiRedirect {

  void init(@Observes Router router) {
    router
        .route("/ci")
        .method(HttpMethod.GET)
        .method(HttpMethod.HEAD)
        .handler(
            rc -> {
              // Vert.x path routes are trailing-slash tolerant: route("/ci") matches /ci/ too,
              // and answering the slash form here would sit AHEAD of Quinoa and loop the
              // redirect onto itself. Only the exact bare segment is this route's business.
              if (!"/ci".equals(rc.request().path())) {
                rc.next();
                return;
              }
              String query = rc.request().query();
              rc.response()
                  .setStatusCode(301)
                  .putHeader("Location", query == null ? "/ci/" : "/ci/?" + query)
                  .end();
            });
  }
}
