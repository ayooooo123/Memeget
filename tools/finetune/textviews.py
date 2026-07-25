"""Text views of a meme's tags — shared by the golden builder and the fine-tune
so train/eval text construction can't drift.

Retrieval eval uses ONE canonical caption per meme (`primary_caption`). Training
uses several views per image (`training_views`) for contrastive augmentation, per
docs/memedepot-finetune.md ("Build several text views per image").
"""
from __future__ import annotations

MAX_TAGS = 8


def primary_caption(tags: list[str]) -> str:
    """The canonical description used as the retrieval query for a meme."""
    return ", ".join(tags[:MAX_TAGS])


def training_views(tags: list[str]) -> list[str]:
    """Distinct text views for contrastive training. Deduped, non-empty."""
    views: list[str] = []
    if not tags:
        return views
    views.append(primary_caption(tags))
    # A "meme"-framed view helps CLIP's text tower land in meme space.
    views.append("a meme about " + ", ".join(tags[:5]))
    # Single strongest tags as their own view (template/character names).
    for t in tags[:3]:
        views.append(f"a {t} meme")
    seen, out = set(), []
    for v in views:
        v = v.strip()
        if v and v not in seen:
            seen.add(v)
            out.append(v)
    return out
