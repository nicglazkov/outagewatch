"""Local dev API server with in-memory fakes - no GCP needed.

Serves the real FastAPI app with state seeded from the recorded fixtures, so
the mobile app can be exercised end to end against http://10.0.2.2:8080 from
an Android emulator.

Usage: uv run python scripts/dev_server.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import uvicorn
from tests.fakes import FakeEtaHistory, FakeSlo, FakeStateStore, FakeSubs

from outagewatch.api import main as api_main
from outagewatch.feeds.pge import normalize

FIXTURES = Path(__file__).resolve().parent.parent / "tests" / "fixtures" / "20260709"


class DevLlm:
    def generate(self, system: str, prompt: str) -> str:
        return (
            "This is a local dev explanation. The real one comes from Claude "
            "Haiku using only the outage facts."
        )


class DevCache:
    def __init__(self):
        self.store = {}

    def get(self, key):
        return self.store.get(key)

    def put(self, key, text):
        self.store[key] = text


class DevDeps:
    def __init__(self):
        points = json.loads((FIXTURES / "outages_l4.geojson").read_text())["features"]
        polygons = json.loads((FIXTURES / "outages_l8.geojson").read_text())["features"]
        self.state = FakeStateStore()
        self.state.snapshot = normalize(points=points, points_no_poly=[], polygons=polygons)
        self.subs = FakeSubs()
        self.eta_history = FakeEtaHistory()
        self.slo = FakeSlo([45.0, 120.0, 310.0])
        self.llm = DevLlm()
        self.explain_cache = DevCache()


def main() -> None:
    deps = DevDeps()
    api_main.app.dependency_overrides[api_main.get_deps] = lambda: deps
    print(f"dev server: {len(deps.state.snapshot)} outages loaded from fixtures")
    uvicorn.run(api_main.app, host="0.0.0.0", port=8787)


if __name__ == "__main__":
    main()
