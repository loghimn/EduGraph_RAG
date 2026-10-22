package fu.coreservice.dto.knowledgegraph;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Concept information used to compute an embedding and save a draft",
        example = """
                {"name":"HTTP Requests","aliases":["HTTP Request"],"language":"en"}
                """)
public class ConceptDraftRequest {

    @NotBlank
    @Size(max = 200)
    @Schema(description = "Concept name", example = "HTTP Requests", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @NotNull
    @Size(max = 50)
    @Schema(description = "Equivalent names; defaults to an empty list", example = "[\"HTTP Request\"]")
    private List<@NotBlank @Size(max = 200) String> aliases = List.of();

    @NotNull
    @Pattern(regexp = "vi|en|mixed")
    @Schema(description = "Concept language; defaults to mixed", example = "en",
            allowableValues = {"vi", "en", "mixed"}, defaultValue = "mixed")
    private String language = "mixed";
}
