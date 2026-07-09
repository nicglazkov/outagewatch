"""Build backend/src/outagewatch/data/ca_zips.csv from the Census ZCTA gazetteer.

Usage: uv run python scripts/make_zip_table.py path/to/2024_Gaz_zcta_national.txt

Keeps California-range ZCTAs (90000-96199) with their interior point and an
effective radius derived from land area (radius of the equal-area circle plus
a 2km buffer, capped at 30km). Census gazetteer data is public domain.
"""

from __future__ import annotations

import csv
import math
import sys
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "src" / "outagewatch" / "data" / "ca_zips.csv"
BUFFER_KM = 2.0
MAX_RADIUS_KM = 30.0


def main(gazetteer_path: str) -> None:
    rows = []
    with open(gazetteer_path, encoding="utf-8") as f:
        reader = csv.DictReader(f, delimiter="\t")
        reader.fieldnames = [name.strip() for name in reader.fieldnames]
        for row in reader:
            zcta = row["GEOID"].strip()
            if not ("90000" <= zcta <= "96199"):
                continue
            aland_m2 = float(row["ALAND"].strip() or 0)
            radius = min(math.sqrt(aland_m2 / math.pi) / 1000 + BUFFER_KM, MAX_RADIUS_KM)
            rows.append(
                (
                    zcta,
                    round(float(row["INTPTLAT"].strip()), 5),
                    round(float(row["INTPTLONG"].strip()), 5),
                    round(radius, 2),
                )
            )

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["zip", "lat", "lon", "radius_km"])
        writer.writerows(sorted(rows))
    print(f"wrote {len(rows)} CA ZCTAs to {OUT}")


if __name__ == "__main__":
    main(sys.argv[1])
