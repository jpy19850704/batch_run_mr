@echo off
setlocal

chcp 65001 >nul

set "SCRIPT_DIR=%~dp0"
set "ENGINE_ROOT=%SCRIPT_DIR%.."

cd /d "%ENGINE_ROOT%"
if errorlevel 1 (
  echo [ERROR] 无法进入目录: %ENGINE_ROOT%
  exit /b 1
)

echo [STEP] 编译 mr-core...
call mvn -pl mr-core -am -q "-DskipTests" compile
if errorlevel 1 (
  echo [ERROR] mr-core 编译失败
  exit /b 1
)

echo [STEP] 生成运行 classpath...
call mvn -f mr-core\pom.xml -q -DskipTests dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
if errorlevel 1 (
  echo [ERROR] classpath 生成失败
  exit /b 1
)

set "CP_FILE=mr-core\target\classpath.txt"
if not exist "%CP_FILE%" (
  echo [ERROR] 未找到 classpath 文件: %CP_FILE%
  exit /b 1
)

set /p CP=<"%CP_FILE%"

echo [STEP] 执行字段主数据自动生成器...
java -cp "mr-core\target\classes;%CP%" com.zcyh.mr.loader.ProductInputFieldAutoGenerator
if errorlevel 1 (
  echo [ERROR] 字段主数据生成失败
  exit /b 1
)

echo [OK] 生成完成:
echo   - mr-core\src\main\resources\data\product_input_fields_auto.csv
echo   - mr-core\src\main\resources\data\product_input_fields_auto_diff.md
exit /b 0
