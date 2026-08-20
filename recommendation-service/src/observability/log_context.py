from contextvars import ContextVar


correlation_id_ctx: ContextVar[str | None] = ContextVar(
    "correlation_id",
    default=None,
)

event_id_ctx: ContextVar[str | None] = ContextVar(
    "event_id",
    default=None,
)