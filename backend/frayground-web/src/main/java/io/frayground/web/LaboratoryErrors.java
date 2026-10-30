package io.frayground.web;

import io.frayground.web.dto.LaboratoryDtos.ErrorView;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;

public final class LaboratoryErrors {
  private LaboratoryErrors() {}
  public static ResponseEntity<ErrorView> error(int status,String code,String trace) {
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
        .body(new ErrorView(code,code,status==429 || status==503,trace,Map.of()));
  }
  public static ResponseEntity<ErrorView> transport(int status,String code) {
    return error(status,code,UUID.randomUUID().toString());
  }
  public static ResponseEntity<ErrorView> command(String code,UUID id) {
    String external="ROOM_ERROR".equals(code)?"ROOM_UNAVAILABLE":code;
    int status="ROOM_UNAVAILABLE".equals(external)?503:409;
    return error(status,external,id.toString());
  }
}
