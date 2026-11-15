// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.web.api;

import io.github.rnachoc.aduana.gateway.audit.AuditAppended;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.http.MediaType;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Streams log activity to the console as server-sent events: one event per appended entry, with the
 * fields an activity view needs and never the arguments.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@RestController
public class ActivityController {

  private static final long TIMEOUT_MS = 30 * 60 * 1000L;

  private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

  /**
   * Opens a stream and sends a comment at once, so the browser sees the stream open before the
   * first entry.
   *
   * @return the emitter
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @GetMapping(path = "/api/v1/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events() {
    SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
    emitters.add(emitter);
    emitter.onCompletion(() -> emitters.remove(emitter));
    emitter.onTimeout(() -> emitters.remove(emitter));
    emitter.onError(error -> emitters.remove(emitter));
    try {
      emitter.send(SseEmitter.event().comment("connected"));
    } catch (IOException exception) {
      emitters.remove(emitter);
    }
    return emitter;
  }

  /**
   * Sends an appended entry's summary to every open stream; a broken stream is dropped.
   *
   * @param event the appended entry's summary
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @TransactionalEventListener(fallbackExecution = true)
  public void onAppended(AuditAppended event) {
    for (SseEmitter emitter : emitters) {
      try {
        emitter.send(SseEmitter.event().name("audit").data(event, MediaType.APPLICATION_JSON));
      } catch (IOException | IllegalStateException exception) {
        emitters.remove(emitter);
      }
    }
  }
}
