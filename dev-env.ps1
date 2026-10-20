# Salt Audio Copy dev environment (optional)
# Usage:  . .\dev-env.ps1    (dot-source so it applies to the current session)
# Only points the session at a local JDK 25 (and a local Gradle if present).
# It does not touch system environment variables. A normal build does not need
# this script -- the repository ships the Gradle wrapper (mod\gradlew.bat).
#
# Keep this file ASCII-only: Windows PowerShell reads BOM-less scripts in the
# system ANSI code page, and non-ASCII text can break its parser.

$javaHome = 'D:\dev\jdk-25'
if (-not (Test-Path (Join-Path $javaHome 'bin\java.exe'))) {
    Write-Error "JDK 25 not found at $javaHome\bin\java.exe. Install JDK 25 or edit dev-env.ps1."
    exit 1
}
$env:JAVA_HOME = $javaHome

$gradleHome = 'D:\dev\gradle'
if (Test-Path (Join-Path $gradleHome 'bin\gradle.bat')) {
    $env:GRADLE_HOME = $gradleHome
    $env:Path = "$env:JAVA_HOME\bin;$env:GRADLE_HOME\bin;" + $env:Path
    Write-Host "Gradle: $env:GRADLE_HOME"
} else {
    Remove-Item Env:\GRADLE_HOME -ErrorAction SilentlyContinue
    $env:Path = "$env:JAVA_HOME\bin;" + $env:Path
    Write-Host "Gradle: not found at $gradleHome, using mod\gradlew.bat"
}
$env:PYTHONIOENCODING = 'utf-8'

Write-Host "JAVA_HOME = $env:JAVA_HOME"
# cmd /c keeps java's stderr banner from becoming a PowerShell error record
cmd /c "`"$env:JAVA_HOME\bin\java.exe`" -version 2>&1" | Select-Object -First 1 | ForEach-Object { Write-Host $_ }
