"""Typed pipeline failures used by data, training, and serving gates."""


class EduTwinError(RuntimeError):
    """Base error for a failed EduTwin gate."""


class DataQualityError(EduTwinError):
    """Raised when source data violates a frozen quality contract."""


class LeakageError(DataQualityError):
    """Raised when a feature contract contains forbidden future information."""


class ReproducibilityError(EduTwinError):
    """Raised when two deterministic runs produce different manifests."""


class ModelSelectionError(EduTwinError):
    """Raised when candidate metrics cannot satisfy the frozen selection policy."""
