@echo off
chcp 65001 > nul 2>&1
setlocal
rem 运动会智能编排系统 v2.8.7 —— 嵌入式版启动程序（自带 JDK，无需安装 Java）
rem 用法：start.bat  参数原样透传给 jar，例如：start.bat --app.port=8899 --app.host=::
rem 固定工作目录为脚本所在目录（data/app-config.json、sports_meet.db 均相对此目录）
cd /d "%~dp0"

rem JDK 查找顺序：本目录 -> 上级目录 -> JAVA_HOME -> PATH（随包便携 JDK 优先）
set "JAVA=%~dp0jdk-21.0.12.1+1\bin\java.exe"
if not exist "%JAVA%" set "JAVA=%~dp0..\jdk-21.0.12.1+1\bin\java.exe"
if not exist "%JAVA%" if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"
if not exist "%JAVA%" (
  echo [WARN] 未找到随包 JDK，改用系统 PATH 中的 java - 需自行安装 Java 21 或更高
  set "JAVA=java"
)

echo === 运动会智能编排系统 v2.8.7 - 嵌入式版：前端与 16 个模型都在 jar 内 ===
echo     Java : %JAVA%
echo     访问 : http://localhost:8080 - 端口以 data/app-config.json 或命令行参数为准
echo     停止 : 在本窗口按 Ctrl+C
echo.

"%JAVA%" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%~dp0sports-2.8.7.jar" %*
endlocal
