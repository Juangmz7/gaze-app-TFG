package com.app.socialservice.shared.infrastructure.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class ProcessedEventId implements Serializable {

    private UUID id;
    private TargetDatabase targetDatabase;

    public ProcessedEventId() {
    }

    public ProcessedEventId(UUID id, TargetDatabase targetDatabase) {
        this.id = id;
        this.targetDatabase = targetDatabase;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProcessedEventId that)) {
            return false;
        }
        return Objects.equals(id, that.id) && targetDatabase == that.targetDatabase;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, targetDatabase);
    }
}
