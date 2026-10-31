@echo off
@setlocal EnableExtensions EnableDelayedExpansion

REM Auto-detect JAVA_HOME if not explicitly set in environment
if "%JAVA_HOME%"=="" (
  for /d %%J in ("C:\Program Files\Java\jdk*") do (
    if exist "%%J\bin\java.exe" set "JAVA_HOME=%%J"
  )
)

set "DIR=%~dp0"
set "WRAPPER_PROPERTIES=%DIR%.mvn\wrapper\maven-wrapper.properties"

if not exist "%WRAPPER_PROPERTIES%" (
  echo Error: %WRAPPER_PROPERTIES% not found.
  exit /b 1
)

for /F "usebackq tokens=1,2 delims==" %%A in ("%WRAPPER_PROPERTIES%") do (
  if "%%A"=="distributionUrl" set "DISTRIBUTION_URL=%%B"
)

for %%F in ("%DISTRIBUTION_URL%") do (
  set "DIST_FILE=%%~nxF"
  set "DIST_BASE_NAME=%%~nF"
)

set "MAVEN_USER_HOME=%USERPROFILE%\.m2"
set "MAVEN_WRAPPER_DIR=%MAVEN_USER_HOME%\wrapper\dists\%DIST_BASE_NAME%"

REM Check if distribution is already unpacked
for /d %%D in ("%MAVEN_WRAPPER_DIR%\*") do (
  for /d %%M in ("%%D\apache-maven*") do (
    if exist "%%M\bin\mvn.cmd" (
      set "MVN_CMD=%%M\bin\mvn.cmd"
      goto runMaven
    )
  )
  if exist "%%D\bin\mvn.cmd" (
    set "MVN_CMD=%%D\bin\mvn.cmd"
    goto runMaven
  )
)

echo Downloading Maven from %DISTRIBUTION_URL%...
powershell -NoProfile -Command "New-Item -ItemType Directory -Force -Path '%MAVEN_WRAPPER_DIR%\download' | Out-Null; [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; (New-Object Net.WebClient).DownloadFile('%DISTRIBUTION_URL%', '%MAVEN_WRAPPER_DIR%\download\%DIST_FILE%'); Expand-Archive -Path '%MAVEN_WRAPPER_DIR%\download\%DIST_FILE%' -DestinationPath '%MAVEN_WRAPPER_DIR%\download\unpacked' -Force; Move-Item -Path '%MAVEN_WRAPPER_DIR%\download\unpacked\*' -Destination '%MAVEN_WRAPPER_DIR%\default' -Force; Remove-Item '%MAVEN_WRAPPER_DIR%\download' -Recurse -Force"
if errorlevel 1 (
  echo Error downloading or extracting Maven.
  exit /b 1
)

for /d %%D in ("%MAVEN_WRAPPER_DIR%\*") do (
  for /d %%M in ("%%D\apache-maven*") do (
    if exist "%%M\bin\mvn.cmd" (
      set "MVN_CMD=%%M\bin\mvn.cmd"
      goto runMaven
    )
  )
  if exist "%%D\bin\mvn.cmd" (
    set "MVN_CMD=%%D\bin\mvn.cmd"
    goto runMaven
  )
)

:runMaven
"%MVN_CMD%" %*
exit /b %ERRORLEVEL%
