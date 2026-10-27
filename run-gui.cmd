@echo off
rem NanoMatch GUI demo (Windows). Builds the project first if target\benchmarks.jar is missing.
rem Options: --engine lockfree (default) or --engine baseline
setlocal
cd /d "%~dp0"
if not exist "target\benchmarks.jar" (
  echo Building NanoMatch: mvn clean package -DskipTests
  call mvn -q clean package -DskipTests
  if errorlevel 1 exit /b 1
)
java -cp "target\benchmarks.jar" com.nanomatch.gui.NanoMatchApp %*
