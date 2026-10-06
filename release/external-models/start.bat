@echo off
chcp 65001 > nul 2>&1
setlocal
rem 运动会智能编排系统 v2.8.7 —— 外置模型版启动程序（自带 JDK，模型在 jar 外）
rem 模型不进 jar，从同目录的 models 读取（换模型无需重新打包）
rem 用法：start.bat  参数原样透传给 jar，例如：start.bat --app.port=8899
cd /d "%~dp0"

if not exist "%~dp0models" (
  echo [ERROR] 未找到模型目录 %~dp0models
  echo         外置模型版必须把 models 目录放在 jar 旁边
  pause
  exit /b 1
)

set MODELDIR=%~dp0models
for /f %%c in ('dir /b "%~dp0models\*.onnx" 2^>nul ^| find /c /v ""') do set ONNXCOUNT=%%c
if "%ONNXCOUNT%"=="0" (
  echo [ERROR] 模型目录里没有 .onnx 文件：%MODELDIR%
  pause
  exit /b 1
)
if %ONNXCOUNT% LSS 16 echo [WARN] 模型目录只有 %ONNXCOUNT% 个 onnx（应为 16 个）—— 缺失的模型会让对应 AI 能力静默退化为规则模式

rem JDK 查找顺序：本目录 -> 上级目录 -> JAVA_HOME -> PATH（随包便携 JDK 优先）
set "JAVA=%~dp0jdk-21.0.12.1+1\bin\java.exe"
if not exist "%JAVA%" set "JAVA=%~dp0..\jdk-21.0.12.1+1\bin\java.exe"
if not exist "%JAVA%" if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"
if not exist "%JAVA%" (
  echo [WARN] 未找到随包 JDK，改用系统 PATH 中的 java - 需自行安装 Java 21 或更高
  set "JAVA=java"
)

echo === 运动会智能编排系统 v2.8.7 - 外置模型版：模型从 jar 外的 models 目录读取 ===
echo     Java : %JAVA%
echo     模型 : %MODELDIR% - %ONNXCOUNT% 个 onnx
echo     访问 : http://localhost:8080 - 端口以 data/app-config.json 或命令行参数为准
echo     停止 : 在本窗口按 Ctrl+C
echo.

"%JAVA%" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%~dp0sports-2.8.7.jar" "--sports.schedule.ai.model-dir=%MODELDIR%" %*
endlocal
