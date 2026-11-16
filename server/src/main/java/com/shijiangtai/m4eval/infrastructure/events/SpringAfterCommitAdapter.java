package com.shijiangtai.m4eval.infrastructure.events;

import com.shijiangtai.m4eval.application.port.AfterCommitPort;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class SpringAfterCommitAdapter implements AfterCommitPort {

    @Override
    public void afterCommit(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
        } else {
            action.run();
        }
    }

    @Override
    public <T> T afterCommit(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.get();
                        }
                    });
            return null;
        }
        return action.get();
    }
}
