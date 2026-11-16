package com.shijiangtai.m7teacher.infrastructure.event;

import com.shijiangtai.m7teacher.domain.EvaluationTerminalEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Adapter surface for the M4 publisher.
 *
 * <p>M4 owns the terminal-event publication contract (06 / 07 §6.4). Until M4 is implemented,
 * this adapter lets its future adapter publish the M7 event shape without coupling M7 to M4
 * internals; the scheduled {@code FinalScoreService.reconcile()} is the loss fallback.
 */
@Component
public class M7EvaluationTerminalAdapter {

    private final ApplicationEventPublisher publisher;

    public M7EvaluationTerminalAdapter(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void publish(EvaluationTerminalEvent event) {
        publisher.publishEvent(event);
    }
}
