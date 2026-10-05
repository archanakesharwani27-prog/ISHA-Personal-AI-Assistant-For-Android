@echo off
set "JAVA_HOME=D:\applications\jdk-17"
set "PATH=D:\applications\jdk-17\bin;%PATH%"
cd /d "%~dp0android"
call gradlew.bat assembleDebug --daemon
