"""Environment-driven configuration. No secrets in code; everything from env or ADC."""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    project_id: str = os.environ.get("GOOGLE_CLOUD_PROJECT", "outagewatch")
    data_bucket: str = os.environ.get("DATA_BUCKET", "outagewatch-data")
    record_raw_snapshots: bool = os.environ.get("RECORD_RAW", "1") == "1"
    anthropic_model: str = os.environ.get("EXPLAIN_MODEL", "claude-haiku-4-5-20251001")


def settings() -> Settings:
    return Settings()
