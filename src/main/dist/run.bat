@echo off
REM Convenience launcher for the coreJ CLI distribution.
REM %~dp0 expands to this script's directory (with a trailing backslash), so the
REM bundle is fully relocatable.
java %JAVA_OPTS% -jar "%~dp0cumba-oss-corej-cli.jar" %*
