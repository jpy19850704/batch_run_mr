param(
    [string]$DbUrl = "jdbc:h2:file:./data/mr_input_store;MODE=MySQL;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1",
    [string]$DbUser = "sa",
    [string]$DbPassword = "",
    [string]$DataDir = "./src/main/resources/data",
    [string]$SchemaPath = "./src/main/resources/db/mr_input_schema.sql"
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$buildDir = Join-Path $scriptDir ".build"
if (!(Test-Path $buildDir)) {
    New-Item -Path $buildDir -ItemType Directory | Out-Null
}

$m2Root = Join-Path $env:USERPROFILE ".m2\repository"

$h2Repo = Join-Path $m2Root "com\h2database\h2"
$h2Jar = Get-ChildItem $h2Repo -Recurse -Filter "h2-*.jar" |
    Sort-Object FullName -Descending |
    Select-Object -First 1 -ExpandProperty FullName
if ([string]::IsNullOrWhiteSpace($h2Jar)) {
    throw "H2 jar not found. Build mr-app first."
}

$fastjsonRepo = Join-Path $m2Root "com\alibaba\fastjson2\fastjson2"
$fastjsonJar = Get-ChildItem $fastjsonRepo -Recurse -Filter "fastjson2-*.jar" |
    Sort-Object FullName -Descending |
    Select-Object -First 1 -ExpandProperty FullName
if ([string]::IsNullOrWhiteSpace($fastjsonJar)) {
    throw "fastjson2 jar not found. Build project first."
}

$javaFile = Join-Path $scriptDir "SyncInputDataToH2.java"
if (!(Test-Path $javaFile)) {
    throw "SyncInputDataToH2.java not found: $javaFile"
}

$compileCp = "$h2Jar;$fastjsonJar"
& javac -encoding UTF-8 -cp $compileCp -d $buildDir $javaFile

$runCp = "$buildDir;$h2Jar;$fastjsonJar"
$safePassword = $DbPassword
if ($null -eq $safePassword -or $safePassword -eq "") {
    $safePassword = "__EMPTY__"
}
$projectRoot = Split-Path $scriptDir -Parent
Push-Location $projectRoot
try {
    & java -cp $runCp SyncInputDataToH2 $DbUrl $DbUser $safePassword $DataDir $SchemaPath
}
finally {
    Pop-Location
}
