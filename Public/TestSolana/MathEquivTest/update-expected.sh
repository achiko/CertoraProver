#!/usr/bin/env bash

# This script updates expectedDefault.txt with the output from the latest run

set -euo pipefail

# Find the latest emv-* directory
latest=$(ls -d emv-*/ 2>/dev/null | sort | tail -1)
if [[ -z "$latest" ]]; then
    echo "No emv-* directories found" >&2
    exit 1
fi

regression_output="$latest/Reports/regressionOutput.txt"
if [[ ! -f "$regression_output" ]]; then
    echo "Not found: $regression_output" >&2
    exit 1
fi

grep "Pattern-Rewriter" "$regression_output" | grep -v "rule_not_vacuous_cvlr" | sort -u > expectedDefault.txt
echo "Wrote $(wc -l < expectedDefault.txt) lines from $regression_output to expectedDefault.txt"
