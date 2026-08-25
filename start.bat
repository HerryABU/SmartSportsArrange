@echo off
chcp 65001 > nul 2>&1
rem 固定工作目录为脚本所在目录（data/app-config.json、sports_meet.db 均相对此目录）
cd /d "%~dp0"
java -jar sports-1.0.0.jar %*
