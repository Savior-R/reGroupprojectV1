package com.shijiangtai.m3classroom.api;

import com.shijiangtai.m3classroom.application.command.CompletePartCommand;
import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage.PartEtag;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.shared.domain.SessionId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Path/body mapping for {@link RecordingController}. */
@Component
public class RecordingRequestMapper {

    public SessionId toSessionId(UUID value) {
        return new SessionId(Objects.requireNonNull(value, "sessionId"));
    }

    public StreamType toStreamType(String value) {
        return StreamType.fromWire(value);
    }

    public CompletePartCommand toCompleteCommand(CompleteRequest request) {
        Objects.requireNonNull(request, "request");
        List<PartEtag> parts = request.parts() == null
                ? List.of()
                : request.parts().stream()
                        .map(part -> new PartEtag(part.partNumber(), part.etag()))
                        .toList();
        return new CompletePartCommand(
                parts, request.durationMs(), request.size(), request.mime());
    }

    public record PartUrlRequest(int partNumber) {
    }

    public record CompleteRequest(
            List<PartRequest> parts, long durationMs, long size, String mime) {
    }

    public record PartRequest(int partNumber, String etag) {
    }
}
