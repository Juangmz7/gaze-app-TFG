from dataclasses import dataclass

from shared.enum.interaction_metric import InteractionMetric


@dataclass(frozen=True, slots=True)
class InteractionMetricUpdate:
    metric: InteractionMetric
    raw_delta: int | float | None = None
    decayed_delta: int | float | None = None
