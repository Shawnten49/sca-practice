@echo off
chcp 65001 >nul
title Qwen3.6-35B-A3B 越狱版

cd /d "%~dp0"

:menu
cls
echo ==========================================
echo      Qwen3.6-35B-A3B 越狱版+多模态模型
echo               零度优化版
echo ==========================================
echo.
echo 1. Q4_K_P（4090 推荐）
echo 2. Q4_K_M（稳定版）
echo 3. IQ4_NL（高压缩高质量）
echo 4. IQ2_M（6G/8G 显卡）
echo.
echo ==========================================

set /p choice=请输入数字：

if "%choice%"=="1" (
    llama-server ^
    -m "models\Qwen3.6-35B-A3B-Uncensored-HauhauCS-Aggressive-Q4_K_P.gguf" ^
    --mmproj "models\mmproj-Qwen3.6-35B-A3B-Uncensored-HauhauCS-Aggressive-f16.gguf" ^
    -ngl 999 ^
    -c 131072 ^
    -n 8192 ^
    --host 127.0.0.1 ^
    --port 8080
)

if "%choice%"=="2" (
    llama-server ^
    -m "models\Qwen3.6-35B-A3B-Uncensored-HauhauCS-Aggressive-Q4_K_M.gguf" ^
    --mmproj "models\mmproj-Qwen3.6-35B-A3B-Uncensored-HauhauCS-Aggressive-f16.gguf" ^
    -ngl 999 ^
    -c 131072 ^
    -n 8192 ^
    --host 127.0.0.1 ^
    --port 8080
)

if "%choice%"=="3" (
    llama-server ^
    -m "models\Qwen3.6-35B-A3B-Uncensored-HauhauCS-Aggressive-IQ4_NL.gguf" ^
    --mmproj "models\mmproj-Qwen3.6-35B-A3B-Uncensored-HauhauCS-Aggressive-f16.gguf" ^
    -ngl 999 ^
    -c 131072 ^
    -n 8192 ^
    --host 127.0.0.1 ^
    --port 8080
)

if "%choice%"=="4" (
    llama-server ^
    -m "models\Qwen3.6-35B-A3B-Uncensored-HauhauCS-Aggressive-IQ2_M.gguf" ^
    --mmproj "models\mmproj-Qwen3.6-35B-A3B-Uncensored-HauhauCS-Aggressive-f16.gguf" ^
    -ngl 999 ^
    -c 8192 ^
    -n 4096 ^
    --host 127.0.0.1 ^
    --port 8080
)

pause