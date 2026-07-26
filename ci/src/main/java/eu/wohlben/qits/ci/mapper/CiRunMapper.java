package eu.wohlben.qits.ci.mapper;

import eu.wohlben.qits.ci.dto.CiRunDto;
import eu.wohlben.qits.ci.dto.CiStepDto;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiStep;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "jakarta")
public interface CiRunMapper {

  // Steps are keyed by runId, not a JPA relation — the boundary attaches them explicitly (single
  // -run endpoint only; listings keep steps null).
  @Mapping(target = "steps", ignore = true)
  CiRunDto toDto(CiRun entity);

  CiStepDto toDto(CiStep entity);

  default CiRunDto toDto(CiRun entity, List<CiStep> steps) {
    CiRunDto bare = toDto(entity);
    return new CiRunDto(
        bare.id(),
        bare.repoId(),
        bare.branch(),
        bare.commitSha(),
        bare.status(),
        bare.createdAt(),
        bare.finishedAt(),
        steps.stream().map(this::toDto).toList());
  }
}
