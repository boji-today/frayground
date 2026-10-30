package io.frayground.web;

import io.frayground.application.query.LaboratoryQueryService.*;
import io.frayground.web.dto.LaboratoryDtos.ErrorView;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages="io.frayground.web")
@Profile("laboratory")
public class LaboratoryExceptionHandler {
  @ExceptionHandler(NotFound.class) ResponseEntity<ErrorView> notFound(NotFound e) { return LaboratoryErrors.transport(404,e.code()); }
  @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
  ResponseEntity<ErrorView> badRequest() { return LaboratoryErrors.transport(400,"INVALID_REQUEST"); }
  @ExceptionHandler(Unavailable.class) ResponseEntity<ErrorView> unavailable() { return LaboratoryErrors.transport(503,"ROOM_UNAVAILABLE"); }
  @ExceptionHandler(RoomEventStreamService.InvalidCursor.class) ResponseEntity<ErrorView> cursor() { return LaboratoryErrors.transport(409,"CURSOR_INVALID"); }
  @ExceptionHandler(RoomEventStreamService.LimitExceeded.class) ResponseEntity<ErrorView> limit() {
    var response=LaboratoryErrors.transport(429,"RATE_LIMITED");
    return ResponseEntity.status(429).header("Retry-After","1").contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(response.getBody());
  }
  @ExceptionHandler(Exception.class) ResponseEntity<ErrorView> unexpected() { return LaboratoryErrors.transport(500,"INTERNAL_ERROR"); }
}
