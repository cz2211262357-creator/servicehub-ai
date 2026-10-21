import json
import logging
import re
from datetime import UTC, datetime


class JSONFormatter(logging.Formatter):
    def format(self, record):
        event = str(record.msg)
        data = {
            "time": datetime.now(UTC).isoformat(),
            "level": record.levelname,
            "event": event if re.fullmatch(r"[a-zA-Z0-9_.-]{1,80}", event) else "application_event",
        }
        for field in (
            "request_id",
            "method",
            "route",
            "status",
            "duration_ms",
            "exception_type",
            "error_type",
            "job_id",
        ):
            if hasattr(record, field):
                data[field] = getattr(record, field)
        return json.dumps(data, ensure_ascii=False, default=str)


def configure_logging():
    logger = logging.getLogger("servicehub")
    if not logger.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(JSONFormatter())
        logger.addHandler(handler)
    logger.setLevel(logging.INFO)
    logger.propagate = False
