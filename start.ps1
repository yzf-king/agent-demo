# 启动 agent-demo。API key 只从注册表(User 作用域)读进当前进程，不写进任何文件。
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File start.ps1 [-Port 8083]
param([string]$Port = "8083")

$env:DEEPSEEK_API_KEY = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'User')
$len = if ($env:DEEPSEEK_API_KEY) { $env:DEEPSEEK_API_KEY.Length } else { 0 }
Write-Host ("KEY presence: DEEPSEEK=" + $len)
Write-Host ("PORT: " + $Port)

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { Write-Host 'MVN_NOT_FOUND'; exit 1 }
cmd /c "mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=$Port"
