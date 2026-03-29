[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

param(
    [string]$H2Url = "jdbc:h2:file:E:/zcyh_mr/H2db/mr_input_store;MODE=MySQL;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1",
    [string]$MySqlHost = "127.0.0.1",
    [int]$MySqlPort = 3306,
    [string]$MySqlUser = "root",
    [string]$MySqlDatabase = "mr_engine"
)

# 说明：
# 1) 本脚本用于迁移前后行数对账
# 2) H2 使用 2.1.214 版本工具兼容当前库文件

$h2Jar = Resolve-Path (Join-Path $PSScriptRoot "..\..\..\..\lib\h2-2.1.214.jar")
$h2Sql = Get-Content -Path (Join-Path $PSScriptRoot "00_count_check_h2.sql") -Raw -Encoding UTF8
$mySql = Get-Content -Path (Join-Path $PSScriptRoot "04_count_check_mysql.sql") -Raw -Encoding UTF8

Write-Host "========== H2 行数 =========="
java -cp $h2Jar org.h2.tools.Shell -url $H2Url -user sa -sql $h2Sql

Write-Host ""
Write-Host "========== MySQL 行数 =========="
mysql --host=$MySqlHost --port=$MySqlPort --user=$MySqlUser --database=$MySqlDatabase -e $mySql

