#!/usr/bin/env python3
"""Generate annex_c.json from the FIFA 2026 Annex C matrix (docs/ANNEX_C.txt).

The Annex C matrix lists, for each of the 495 possible combinations of 8
advancing third-place teams, which group winner each third-place team faces.

Output format (annex_c.json):
    {
        "EFGHIJKL": {
            "E": "A",
            "J": "B",
            ...
        },
        ...
    }

The top-level key is the 8 third-place group letters sorted alphabetically.
The inner dict maps each third-place group letter to the group-winner letter
it plays against.
"""

import json
from pathlib import Path

# Resolve paths relative to the project root (parent of this script's dir)
SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent
INPUT_FILE = PROJECT_ROOT / "docs" / "ANNEX_C.txt"
OUTPUT_FILE = PROJECT_ROOT / "src" / "main" / "resources" / "annex_c.json"


def parse_annex_c(input_path):
    """Parse the Annex C text file into a nested dictionary.

    Args:
        input_path: Path to the tab-delimited ANNEX_C.txt file.

    Returns:
        A dict keyed by the sorted 8-letter third-place string, whose values
        are dicts mapping each third-place group letter to the winner group
        letter it faces.
    """
    with open(input_path, "r", encoding="utf-8") as f:
        lines = f.readlines()

    # --- Header row ---
    # e.g. "Option\t1A\t1B\t1D\t1E\t1G\t1I\t1K\t1L"
    header = lines[0].split()
    # Strip the "1" prefix from each winner column -> ["A", "B", "D", ...]
    winner_letters = [col[1:] for col in header[1:]]

    matrix = {}

    for line in lines[1:]:
        columns = line.split()
        if not columns:
            continue  # skip blank lines

        # columns[0] is the option number (1-495); columns[1:] are third-place
        # teams like "3E", "3J", ... -> strip "3" prefix -> "E", "J", ...
        third_teams = [col[1:] for col in columns[1:]]

        # Map each third-place letter to the winner letter it faces (column order)
        mapping = {}
        for third, winner in zip(third_teams, winner_letters):
            mapping[third] = winner

        # Key: the 8 third-place letters sorted alphabetically, joined
        key = "".join(sorted(third_teams))

        matrix[key] = mapping

    return matrix


def main():
    """Parse the input file and write the JSON output."""
    matrix = parse_annex_c(INPUT_FILE)
    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        json.dump(matrix, f, indent=2)
        f.write("\n")  # trailing newline for POSIX-friendly files
    print("Generated {} with {} entries".format(OUTPUT_FILE, len(matrix)))


if __name__ == "__main__":
    main()