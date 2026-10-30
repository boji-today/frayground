package io.frayground.web;

import io.frayground.application.query.LaboratoryReadModels.MatchData;
import io.frayground.web.dto.LaboratoryDtos.ResultView;

/** Projects a stored match snapshot into the public game object. */
public interface BoardProjector {
  Object project(MatchData data, ResultView result);
}
