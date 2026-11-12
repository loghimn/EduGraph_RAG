package fu.coreservice.dto.knowledgegraph;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A concept or relationship mutation returns only the fields applicable to that operation. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GraphMutationResponse(
        @JsonProperty("graph_version") Long graphVersion,
        String id, String source, String target, String status) {
}
