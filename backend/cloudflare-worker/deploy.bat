@echo off
title ISHA Cloudflare Gateway Deployer
echo ====================================================
echo      ISHA AI Cloudflare Edge Gateway Deployer
echo ====================================================
echo.

cd /d "%~dp0"

echo [1/3] Checking dependencies...
if not exist node_modules (
    echo Installing wrangler...
    call npm install
)

echo.
echo [2/3] Checking Cloudflare authentication...
call npx wrangler whoami >nul 2>&1
if %errorlevel% neq 0 (
    echo Logging in to Cloudflare...
    call npx wrangler login
)

echo.
echo [3/3] Deploying to Cloudflare Workers...
call npx wrangler deploy

echo.
echo ====================================================
echo Deployment complete!
echo If you haven't set your GEMINI_API_KEY secret yet, run:
echo    npx wrangler secret put GEMINI_API_KEY
echo ====================================================
pause
