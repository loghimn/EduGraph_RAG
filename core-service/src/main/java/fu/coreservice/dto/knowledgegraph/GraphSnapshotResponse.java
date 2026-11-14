package fu.coreservice.dto.knowledgegraph;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record GraphSnapshotResponse(
        @JsonProperty("graph_version") Long graphVersion,
        List<Concept> nodes, List<Relationship> edges) {

    public record Concept(String id, String name, List<String> aliases, String language,
                          @JsonProperty("review_status") String reviewStatus, String origin) {}

    public record Relationship(String source, String target, String status, Double score, String evidence,
                               @JsonProperty("evidence_chunk_id") String evidenceChunkId,
                               String note, @JsonProperty("reviewed_by") String reviewedBy,
                               @JsonProperty("reviewed_at") Long reviewedAt) {}
}
