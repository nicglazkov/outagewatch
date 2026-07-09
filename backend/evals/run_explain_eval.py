"""Run the AI explain eval against the real model.

Usage: uv run python evals/run_explain_eval.py  (needs ANTHROPIC_API_KEY)

Asserts each of the 20 outage states produces an explanation containing the
expected key facts and no hallucinated times/causes. Exits non-zero on failure.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from explain_cases import CASES  # noqa: E402

from outagewatch.explain import AnthropicClient, explain_outage  # noqa: E402


class NoCache:
    def get(self, key: str) -> str | None:
        return None

    def put(self, key: str, text: str) -> None:
        pass


def main() -> int:
    llm = AnthropicClient()
    failures = 0
    for case in CASES:
        history = [{"eta": None, "observed_at": ""}] * case.eta_history_len
        text = explain_outage(case.item, llm, NoCache(), history)
        problems = []
        for needle in case.must_contain:
            if needle not in text:
                problems.append(f"missing expected fact: {needle!r}")
        for pattern in case.must_not_match:
            for m in re.finditer(pattern, text):
                # A clock time is only a hallucination if it's not one we provided.
                if not any(m.group(0) in fact for fact in case.must_contain):
                    problems.append(f"hallucination guard tripped: {m.group(0)!r}")
        status = "PASS" if not problems else "FAIL"
        print(f"[{status}] {case.name}")
        if problems:
            failures += 1
            for p in problems:
                print(f"    {p}")
            print(f"    output: {text[:300]}")
    print(f"\n{len(CASES) - failures}/{len(CASES)} passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
