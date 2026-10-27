@echo off
rem NanoMatch terminal demo (Windows): scenarios on both engines, a live parity check, and the measured results.
rem Options: --engine baseline^|lockfree^|both   --scenario match^|nomatch^|partial^|price^|time^|execprice^|sweep^|all
rem          --no-live   --no-results
setlocal
cd /d "%~dp0"
if not exist "target\benchmarks.jar" (
  echo Building NanoMatch: mvn clean package -DskipTests
  call mvn -q clean package -DskipTests
  if errorlevel 1 exit /b 1
)
java -cp "target\benchmarks.jar" com.nanomatch.demo.TerminalDemo %*
