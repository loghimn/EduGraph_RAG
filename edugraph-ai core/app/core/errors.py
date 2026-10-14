class AppError(Exception):
    """Base exception for application errors."""
    pass

class LLMUnavailableError(AppError):
    """Raised when both primary and fallback LLMs fail."""
    pass

class ScannedPDFError(AppError):
    """Raised when a PDF contains too few characters, likely meaning it's a scanned image."""
    pass

class UnsupportedFormatError(AppError):
    """Raised when the document format is not supported (e.g., .doc instead of .docx)."""
    pass

class DatabaseError(AppError):
    """Raised when there is an issue interacting with Neo4j."""
    pass

class DocumentNotFoundError(AppError):
    """Raised when a document is not found in the database or filesystem."""
    pass

class GraphNotDagError(AppError):
    """Raised when the generated or retrieved graph contains a cycle."""
    def __init__(self, message: str, conflicts: list):
        super().__init__(message)
        self.conflicts = conflicts

class PermissionDeniedError(AppError):
    """Raised when a user is not authorized to access a specific resource."""
    pass

class InvalidPathError(AppError):
    """Raised when path traversal is detected or a file path is invalid."""
    pass
