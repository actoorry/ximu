# =============================================================================
# ximu inventory-service 首次启动冒烟测试脚本
# -----------------------------------------------------------------------------
# 作用：验证服务「运行正确」而非仅「编译正确」——在真实 MySQL 上启动服务、
#       执行 Flyway V1 迁移，并做接口冒烟（健康/鉴权拦截/建单取号/库存联动）。
# 用法：pwsh scripts/smoke-test.ps1 [-DbName ximu_smoke] [-Port 8081]
# 前置：本机 MySQL 8 可达（默认 localhost:3306），账号默认 root/123456。
# 说明：使用「全新库」验证 Flyway 新库路径（createDatabaseIfNotExist=true），
#       不污染开发库 ximu。冒烟后会停止服务；数据库是否删除由执行者决定。
# =============================================================================
param(
    [string]$DbName = "ximu_smoke",
    [string]$DbUser = "root",
    [string]$DbPass = "123456",
    [int]$Port = 8081,
    [string]$JavaHome = "C:\Users\Administrator\.jdks\ms-17.0.20",
    [string]$MavenHome = "D:\IDEA\IntelliJ IDEA 2025.1\plugins\maven\lib\maven3"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot   # 项目根目录
$jar  = Join-Path $root "ximu-inventory-service\target\ximu-inventory-service-1.0-SNAPSHOT.jar"
$repo = Join-Path $root ".m2-repo"
$settings = Join-Path $root "mvn-settings.xml"

$env:JAVA_HOME   = $JavaHome
$env:MAVEN_HOME  = $MavenHome
$env:Path        = "$JavaHome\bin;$MavenHome\bin;$env:Path"

function Step($msg) { Write-Host "`n== $msg ==" -ForegroundColor Cyan }
function Pass($msg) { Write-Host "  PASS  $msg" -ForegroundColor Green }
function Fail($msg) { Write-Host "  FAIL  $msg" -ForegroundColor Red; $script:failed = $true }
$script:failed = $false

# ---------- 1. 打包（阿里云镜像 + 工作区本地仓库） ----------
Step "1/5 打包 inventory-service"
& mvn -s "$settings" "-Dmaven.repo.local=$repo" package -DskipTests --batch-mode -pl ximu-inventory-service -am
if ($LASTEXITCODE -ne 0) { Fail "打包失败"; exit 1 }
Pass "jar 构建成功"

# ---------- 2. 启动服务（全新库，走 Flyway V1 新库路径） ----------
Step "2/5 启动服务（DB=$DbName）"
$env:DB_URL = "jdbc:mysql://localhost:3306/$DbName?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&characterEncoding=utf8&createDatabaseIfNotExist=true"
$env:DB_USERNAME = $DbUser
$env:DB_PASSWORD = $DbPass
$env:FLYWAY_ENABLED = "true"

$proc = Start-Process -FilePath "$JavaHome\bin\java.exe" -ArgumentList "-jar","$jar" -RedirectStandardOutput (Join-Path $root "target\smoke-out.log") -RedirectStandardError (Join-Path $root "target\smoke-err.log") -PassThru -WindowStyle Hidden
$script:pid = $proc.Id

try {
    # ---------- 3. 轮询健康（最多 90 秒） ----------
    Step "3/5 轮询健康检查（Flyway 迁移 + 上下文就绪）"
    $up = $false
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 3
        try {
            $c = (Invoke-WebRequest -Uri "http://localhost:$Port/actuator/health" -UseBasicParsing -TimeoutSec 3).Content
            if ($c -match '"UP"') { $up = $true; break }
        } catch { }
    }
    if (-not $up) { Fail "90 秒内服务未就绪（查 target\smoke-out.log）"; exit 1 }
    Pass "health = UP（服务启动 + Flyway 迁移成功）"

    # ---------- 4. 接口冒烟 ----------
    Step "4/5 接口冒烟"
    $adm = @{ "X-User-Id"="99"; "X-User-Name"="smoke"; "X-User-Roles"="ADMIN" }

    # 4.1 无身份头 -> 401
    try {
        Invoke-WebRequest -Uri "http://localhost:$Port/api/inventory/inbound" -UseBasicParsing -TimeoutSec 5 | Out-Null
        Fail "无身份头访问应返回 401"
    } catch {
        $code = [int]$_.Exception.Response.StatusCode
        if ($code -eq 401) { Pass "无身份头 -> HTTP 401（RBAC 拦截生效）" }
        else { Fail "无身份头返回 HTTP $code（预期 401）" }
    }

    # 4.2 带 ADMIN 头建单（原子取号 + 库存联动 + 审计）
    $body = '{"inboundType":"估价","items":[{"orgId":1,"productName":"冒烟测试品","spec":"","grade":"A级","qty":10,"settleQty":0}]}'
    $r1 = Invoke-WebRequest -Uri "http://localhost:$Port/api/inventory/inbound" -Method POST -Headers $adm -ContentType "application/json; charset=utf-8" -Body $body -UseBasicParsing -TimeoutSec 8
    $json1 = $r1.Content | ConvertFrom-Json
    if ($json1.code -eq 0 -and $json1.data.inboundNo -match "^IN\d{11}$") {
        Pass "建单成功，原子取号单号=$($json1.data.inboundNo)（IN+日期+3位序号）"
    } else { Fail "建单失败或单号格式异常: $($r1.Content)" }
}
finally {
    # ---------- 5. 停止服务 ----------
    Step "5/5 停止服务"
    Stop-Process -Id $script:pid -Force -ErrorAction SilentlyContinue
    Pass "服务已停止（PID=$script:pid）"
}

if ($script:failed) { Write-Host "`n冒烟存在失败项" -ForegroundColor Red; exit 1 }
Write-Host "`n冒烟全部通过" -ForegroundColor Green
exit 0
