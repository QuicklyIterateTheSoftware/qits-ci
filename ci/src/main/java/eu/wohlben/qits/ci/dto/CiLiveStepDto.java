package eu.wohlben.qits.ci.dto;

/**
 * The step a run is executing <em>right now</em>, and what it has printed so far — the in-memory
 * relay, not the database.
 *
 * <p>It exists so that a mid-run poll is legible instead of looking like a run with missing steps:
 * step rows are written only at each step's end, so between them the run has fewer step rows than
 * its config declares and this is what says which one is producing the gap.
 *
 * <p>Non-null only on the single-run endpoint, and only while the run is {@code RUNNING}. It is
 * memory and dies with the process — the persisted tail on the step row is the record, this is the
 * live convenience. Polling is the whole read path: there is no SSE and no WebSocket for it.
 */
public record CiLiveStepDto(int stepIndex, String output) {}
