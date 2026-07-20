"""Text views of a meme's tags — shared by the golden builder and the fine-tune
so train/eval text construction can't drift. Retrieval eval uses ONE canonical
caption per meme (`primary_caption`); training uses several views per image."""
from __future__ import annotations

MAX_TAGS = 8


def primary_caption(tags: list[str]) -> str:
    return ", ".join(tags[:MAX_TAGS])


def training_views(tags: list[str]) -> list[str]:
    if not tags:
        return []
    views = [primary_caption(tags), "a meme about " + ", ".join(tags[:5])]
    views += [f"a {t} meme" for t in tags[:3]]
    seen, out = set(), []
    for v in views:
        v = v.strip()
        if v and v not in seen:
            seen.add(v)
            out.append(v)
    return out
