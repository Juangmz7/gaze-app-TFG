import logging
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


def log_level_env(name: str, default: int) -> int:
    """A standard logging level name, in any case. A bad value fails at startup
    instead of leaving the service without the logs someone asked for. NOTSET is
    refused because a logger with it just takes its parent's level."""
    raw_value = os.getenv(name)
    if raw_value is None or not raw_value.strip():
        return default
    level = logging.getLevelNamesMapping().get(raw_value.strip().upper())
    if level is None or level == logging.NOTSET:
        raise ValueError(f"{name} must be a logging level name, got {raw_value!r}")
    return level
