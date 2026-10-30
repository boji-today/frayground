package io.frayground.web;

import io.frayground.application.query.LaboratoryReadModels.MatchData;
import io.frayground.web.dto.LaboratoryDtos.BoardView;
import io.frayground.web.dto.LaboratoryDtos.ResultView;

/** Projects a stored match snapshot into the public board view. */
public interface BoardProjector {
  BoardView project(MatchData data, ResultView result);
}
