@ECHO OFF
SETLOCAL EnableExtensions
SET "MVNW_PROJECT_DIR=%~dp0"
SET "MVNW_PROPERTIES=%MVNW_PROJECT_DIR%.mvn\wrapper\maven-wrapper.properties"
SET "MVNW_MAVEN_HOME=%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.11-bin\resilientpay\apache-maven-3.9.11"
SET "MVNW_ARCHIVE=%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.11-bin\resilientpay\apache-maven-3.9.11-bin.zip"
SET "MVNW_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip"
SET "MVNW_SHA=0d7125e8c91097b36edb990ea5934e6c68b4440eef4ea96510a0f6815e7eeadb"

IF NOT EXIST "%MVNW_PROPERTIES%" (
  ECHO Missing %MVNW_PROPERTIES% 1>&2
  EXIT /B 1
)

IF NOT EXIST "%MVNW_MAVEN_HOME%\bin\mvn.cmd" (
  IF NOT EXIST "%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.11-bin\resilientpay" MKDIR "%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.11-bin\resilientpay"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Invoke-WebRequest -UseBasicParsing '%MVNW_URL%' -OutFile '%MVNW_ARCHIVE%'; if ((Get-FileHash '%MVNW_ARCHIVE%' -Algorithm SHA256).Hash.ToLower() -ne '%MVNW_SHA%') { throw 'Maven distribution checksum mismatch' }; Expand-Archive -Force '%MVNW_ARCHIVE%' (Split-Path '%MVNW_ARCHIVE%')"
  IF ERRORLEVEL 1 EXIT /B 1
)

CALL "%MVNW_MAVEN_HOME%\bin\mvn.cmd" -f "%MVNW_PROJECT_DIR%pom.xml" %*
EXIT /B %ERRORLEVEL%
