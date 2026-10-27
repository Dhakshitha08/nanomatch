#!/bin/sh
# NanoMatch terminal demo (macOS / Linux): scenarios on both engines, a live parity check, and the measured results.
# Options: --engine baseline|lockfree|both  --scenario KEY|all  --no-live  --no-results
cd "$(dirname "$0")" || exit 1
if [ ! -f target/benchmarks.jar ]; then
  echo "Building NanoMatch: mvn clean package -DskipTests"
  mvn -q clean package -DskipTests || exit 1
fi
exec java -cp target/benchmarks.jar com.nanomatch.demo.TerminalDemo "$@"
