"""Explain module tests: fact building, prompt closure, caching. No network."""

from datetime import UTC, datetime

from outagewatch.explain import cache_key, explain_outage, facts_from_item
from watcher.types import Item


class FakeLlm:
    def __init__(self, reply="The power is out because of a pole fire."):
        self.reply = reply
        self.calls: list[tuple[str, str]] = []

    def generate(self, system: str, prompt: str) -> str:
        self.calls.append((system, prompt))
        return self.reply


class MemoryCache:
    def __init__(self):
        self.store: dict[str, str] = {}

    def get(self, key):
        return self.store.get(key)

    def put(self, key, text):
        self.store[key] = text


def _item(eta_hour=23, **payload) -> Item:
    return Item(
        id="o1",
        eta=datetime(2026, 7, 9, eta_hour, tzinfo=UTC) if eta_hour else None,
        updated_at=datetime(2026, 7, 9, 18, tzinfo=UTC),
        payload={
            "OUTAGE_CAUSE": "POLE FIRE",
            "EST_CUSTOMERS": 120,
            "CITY": "Santa Rosa",
            **payload,
        },
    )


def test_facts_are_localized_to_pacific():
    facts = facts_from_item(_item(eta_hour=23))  # 23:00 UTC == 4:00 PM PDT
    assert "4:00 PM" in facts.eta_local


def test_facts_handle_missing_eta():
    facts = facts_from_item(_item(eta_hour=None))
    assert facts.eta_local is None
    assert "not available yet" in facts.to_prompt()


def test_eta_changes_counted_from_history():
    history = [{"eta": "a"}, {"eta": "b"}, {"eta": "c"}]
    assert facts_from_item(_item(), history).eta_changes == 2
    assert facts_from_item(_item(), None).eta_changes == 0


def test_prompt_contains_all_facts_and_only_facts():
    facts = facts_from_item(_item(), None)
    prompt = facts.to_prompt()
    assert "POLE FIRE" in prompt
    assert "120" in prompt
    assert "Santa Rosa" in prompt
    assert "the only facts you may use" in prompt


def test_explain_caches_per_outage_update():
    llm, cache = FakeLlm(), MemoryCache()
    item = _item()
    first = explain_outage(item, llm, cache)
    second = explain_outage(item, llm, cache)
    assert first == second
    assert len(llm.calls) == 1  # second call served from cache


def test_cache_key_changes_when_outage_updates():
    a = _item()
    b = Item(
        id="o1",
        eta=a.eta,
        updated_at=datetime(2026, 7, 9, 19, tzinfo=UTC),
        payload=a.payload,
    )
    assert cache_key(a) != cache_key(b)


def test_psps_flag_reaches_prompt():
    facts = facts_from_item(_item(is_psps=True))
    assert '"is_planned_psps_shutoff": true' in facts.to_prompt()


def test_em_dashes_stripped_from_output():
    llm = FakeLlm("The power is out — a pole fire — since 9am. Back 2 to 4pm.")
    text = explain_outage(_item(), llm, MemoryCache())
    assert "—" not in text
    assert "–" not in text
    assert "The power is out, a pole fire, since 9am." in text
