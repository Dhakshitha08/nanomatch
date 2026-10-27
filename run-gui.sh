#!/bin/sh
# NanoMatch GUI demo (macOS / Linux). Builds the project first if target/benchmarks.jar is missing.
# Options: --engine lockfree (default) or --engine baseline
cd "$(dirname "$0")" || exit 1
if [ ! -f target/benchmarks.jar ]; then
  echo "Building NanoMatch: mvn clean package -DskipTests"
  mvn -q clean package -DskipTests || exit 1
fi
exec java -cp target/benchmarks.jar com.nanomatch.gui.NanoMatchApp "$@"
