#!/usr/bin/env bash
set -euo pipefail
ES="${ES_URIS:-http://localhost:9200}"
INDEX="booking_wide"
MAPPING_FILE="$(dirname "$0")/booking-wide-index.json"
curl -sS -X PUT "$ES/$INDEX" -H 'Content-Type: application/json' --data-binary @"$MAPPING_FILE"
echo "Created index: $INDEX"
