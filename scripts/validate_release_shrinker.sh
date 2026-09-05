#!/usr/bin/env bash
set -euo pipefail

mapping_dir="${1:-app/build/outputs/mapping/release}"
mapping_file="$mapping_dir/mapping.txt"
seeds_file="$mapping_dir/seeds.txt"

for required_file in "$mapping_file" "$seeds_file"; do
  if [[ ! -f "$required_file" ]]; then
    echo "Missing Release shrinker output: $required_file" >&2
    exit 66
  fi
done

require_exact_line() {
  local file="$1"
  local expected="$2"
  if ! grep -Fqx "$expected" "$file"; then
    echo "Release shrinker contract missing: $expected" >&2
    exit 65
  fi
}

# Flogger discovers MediaPipe's enclosing class through real stack frames.
require_exact_line "$mapping_file" \
  "com.google.common.flogger.FluentLogger -> com.google.common.flogger.FluentLogger:"
require_exact_line "$mapping_file" \
  "com.google.common.flogger.backend.system.StackBasedCallerFinder -> com.google.common.flogger.backend.system.StackBasedCallerFinder:"
require_exact_line "$mapping_file" \
  "com.google.common.flogger.util.CallerFinder -> com.google.common.flogger.util.CallerFinder:"

# protobuf-javalite looks up generated backing fields by their source names.
require_exact_line "$seeds_file" \
  "com.google.protobuf.Any: java.lang.String typeUrl_"
require_exact_line "$seeds_file" \
  "com.google.protobuf.Any: com.google.protobuf.ByteString value_"

echo "Release shrinker contracts verified."
