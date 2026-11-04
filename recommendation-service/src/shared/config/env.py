import os


def positive_int_env(name: str, default: int) -> int:
    """A positive integer environment variable. A bad value fails at startup
    instead of surfacing later as a pool or a port that cannot work."""
    raw_value = os.getenv(name)
    if raw_value is None or not raw_value.strip():
        return default
    try:
        value = int(raw_value)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer, got {raw_value!r}") from exc
    if value <= 0:
        raise ValueError(f"{name} must be positive, got {value}")
    return value
