import logging
import os
import time


# Field 22 of /proc/<pid>/stat (starttime, in clock ticks since boot), counted
# from the first field after the process name, which is field 3
_STAT_STARTTIME_INDEX = 22 - 3

logger = logging.getLogger(__name__)


## Measures the startup setup, like Spring Boot's "Started ... in N seconds (process
## running for M)". Created as early as possible in the app module, logged once the
## app is ready to serve.
class StartupTimer:
    def __init__(self) -> None:
        self._started_at = time.perf_counter()

    def log_started(self, service_name: str) -> None:
        setup_seconds = time.perf_counter() - self._started_at
        process_seconds = process_uptime_seconds()

        if process_seconds is None:
            logger.info("Started %s in %.3f seconds", service_name, setup_seconds)
        else:
            logger.info(
                "Started %s in %.3f seconds (process running for %.3f)",
                service_name,
                setup_seconds,
                process_seconds,
            )


## Seconds since the process started, which also covers the interpreter start and the
## imports done before the app module (opentelemetry-instrument, faststream). None
## outside Linux, where /proc does not exist.
def process_uptime_seconds() -> float | None:
    try:
        with open("/proc/self/stat", encoding="ascii") as stat_file:
            stat = stat_file.read()
        with open("/proc/uptime", encoding="ascii") as uptime_file:
            system_uptime = float(uptime_file.read().split()[0])
        # The process name can contain spaces and parentheses: split after the last ')'
        start_ticks = int(stat.rsplit(")", 1)[1].split()[_STAT_STARTTIME_INDEX])
        return system_uptime - start_ticks / os.sysconf("SC_CLK_TCK")
    except (OSError, ValueError, IndexError, AttributeError):
        return None
