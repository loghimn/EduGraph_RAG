package fu.coreservice.entity;

import jakarta.persistence.*;
import lombok.*;
import jakarta.validation.constraints.Positive;

@Entity
@Table(name = "message_citations", uniqueConstraints = {
        @UniqueConstraint(name = "uk_message_citations_message_number", columnNames = {"message_id", "citation_number"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageCitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "citation_id")
    private Long citationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private ChatMessage message;

    // Matches [n] in the answer; nullable only for legacy sources without numbering.
    @Positive
    @Column(name = "citation_number")
    private Integer citationNumber;

    // Source document metadata from the Python sources event. Kept as a scalar
    // so a citation remains readable even after its document is removed.
    @Column(name = "document_id")
    private Long documentId;

    @Column(name = "chunk_index")
    private Integer chunkIndex;

    @Enumerated(EnumType.STRING)
    @Column(name = "citation_type", nullable = false)
    private CitationType citationType;

    @Column(name = "neo4j_concept_id", length = 100)
    private String neo4jConceptId;

    @Column(name = "vector_chunk_id", length = 100)
    private String vectorChunkId;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "snippet_text", columnDefinition = "TEXT")
    private String snippetText;
}
