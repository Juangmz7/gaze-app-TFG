from opentelemetry.instrumentation.logging import LoggingInstrumentor

LoggingInstrumentor().instrument(
    inject_trace_context=True,
)