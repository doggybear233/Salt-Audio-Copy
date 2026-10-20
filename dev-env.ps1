# Salt Audio Copy 开发环境（本机）
# 用法：在 PowerShell 里 . .\dev-env.ps1   （点号加载，注入当前会话）
# 不修改系统环境变量，不污染其它 JDK 使用者。

$env:JAVA_HOME = 'D:\dev\jdk-25'
$env:GRADLE_HOME = 'D:\dev\gradle'
$env:Path = "$env:JAVA_HOME\bin;$env:GRADLE_HOME\bin;" + $env:Path
$env:PYTHONIOENCODING = 'utf-8'

Write-Host "JAVA_HOME = $env:JAVA_HOME"
& "$env:JAVA_HOME\bin\java.exe" -version 2>&1 | Select-Object -First 1
Write-Host "Gradle: $env:GRADLE_HOME"
