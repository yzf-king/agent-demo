# 启动 agent-demo。API key 只从注册表(User 作用域)读进当前进程，不写进任何文件。
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File start.ps1 [-Port 8083] [-KnowledgeBase http://localhost:8081]
param(
    [string]$Port = "8083",
    [string]$KnowledgeBase = "http://localhost:8081"
)

$env:DEEPSEEK_API_KEY = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'User')
$env:AGENT_KNOWLEDGE_BASE_URL = $KnowledgeBase

$len = if ($env:DEEPSEEK_API_KEY) { $env:DEEPSEEK_API_KEY.Length } else { 0 }
Write-Host ("KEY presence: DEEPSEEK=" + $len)
Write-Host ("PORT: " + $Port + "   KNOWLEDGE_BASE: " + $env:AGENT_KNOWLEDGE_BASE_URL)

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { Write-Host 'MVN_NOT_FOUND'; exit 1 }
cmd /c "mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=$Port"
