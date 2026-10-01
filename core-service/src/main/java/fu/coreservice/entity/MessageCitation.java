package fu.coreservice.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "message_citations")
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
