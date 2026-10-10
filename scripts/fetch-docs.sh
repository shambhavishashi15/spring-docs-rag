#!/usr/bin/env bash
# Downloads the Spring docs pages listed in docs/manifest.csv into docs/<file>.html.
# The HTML is not committed: the pages are "Copyright Broadcom. All Rights Reserved.",
# so each clone fetches them itself.
set -euo pipefail

cd "$(dirname "$0")/.."

# Skip the CSV header; titles are not needed for downloading.
tail -n +2 docs/manifest.csv | while IFS=, read -r file url _title; do
  echo "Fetching $url"
  # -f fails on HTTP errors so an error page is never saved as a doc.
  curl -fsSL --retry 3 -o "docs/$file.html" "$url"
done

echo "Done: $(ls docs/*.html | wc -l) pages in docs/"
