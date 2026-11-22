import json
import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image

from score_formats import golden_ids, labelled_images, metrics


class FormatScoreTest(unittest.TestCase):
    def test_one_rank_per_annotated_pair_including_ties(self):
        # In the first row the target formats rank 1st, 4th and 6th. In the second,
        # format 1 ties format 0; the label order puts format 1 second.
        scores = np.array([[6, 5, 4, 3, 2, 1], [9, 9, 2, 3, 4, 5]])
        expected = [1 / 4, 2 / 4, 3 / 4, (1 + 1 / 4 + 1 / 6 + 1 / 2) / 4]
        np.testing.assert_allclose(metrics(scores, [[0, 3, 5], [1]]), expected)

    def test_holdout_membership_exact_tags_and_missing_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "images").mkdir()
            for name in ("a.png", "b.png", "outside.png"):
                Image.new("RGB", (2, 2)).save(root / "images" / name)
            rows = [("a.png", "Rage Comic"), ("b.png", "comic book"),
                    ("missing.png", "rage comic"), ("outside.png", "rage comic")]
            (root / "dataset.jsonl").write_text("".join(
                json.dumps({"image": name, "suffix": tag}) + "\n" for name, tag in rows
            ))
            (root / "meme_dataset_kym.json").write_text(json.dumps([
                {"file": "images/a.png", "tags": ["deep fried", "rage comic"]}
            ]))
            golden = root / "golden.json"
            # Membership must suffice, even when there are no saved vectors or queries.
            golden.write_text(json.dumps({"memes": [{"id": name} for name, _ in rows[:3]]}))
            paths, targets, missing = labelled_images(golden_ids(golden), root, ["comic", "deep fried", "rage comic"])
            self.assertEqual(paths, [root / "images/a.png"])
            self.assertEqual(targets, [[1, 2]])
            self.assertEqual(missing, ["missing.png"])


if __name__ == "__main__":
    unittest.main()
