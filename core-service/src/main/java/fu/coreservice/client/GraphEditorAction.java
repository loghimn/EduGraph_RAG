package fu.coreservice.client;

/** Internal transport operations; never accepted as an arbitrary public action string. */
public enum GraphEditorAction {
    SNAPSHOT, CONCEPT_UPDATE, CONCEPT_REVIEW, EDGE_CREATE, EDGE_UPDATE, EDGE_REVIEW, EDGE_DELETE
}
