@echo off
cd /d "%~dp0"
set "JAVA_HOME=D:\applications\jdk-17"
set "PATH=D:\applications\jdk-17\bin;%PATH%"
set "GRADLE_OPTS=-Djava.security.properties=d:\Projects\aura_shell_backup\android\sha1prng.properties -Xmx2048m"
set "JAVA_OPTS=-Djava.security.properties=d:\Projects\aura_shell_backup\android\sha1prng.properties"
call "C:\Users\Ansh Kesharwani\.gradle\wrapper\dists\gradle-9.3.1-all\9ot9r568e8zfvvd4mn8rbu1j0\gradle-9.3.1\bin\gradle.bat" assembleDebug --console=plain --no-daemon -Dorg.gradle.jvmargs="-Xmx2048m -Djava.security.properties=d:\Projects\aura_shell_backup\android\sha1prng.properties"
if exist "%~dp0app\build\outputs\apk\debug\app-debug.apk" (
    copy /y "%~dp0app\build\outputs\apk\debug\app-debug.apk" "%~dp0app\build\outputs\apk\debug\ISHA.apk"
    copy /y "%~dp0app\build\outputs\apk\debug\app-debug.apk" "%~dp0app\build\outputs\apk\debug\ISHA-debug.apk"
    copy /y "%~dp0app\build\outputs\apk\debug\app-debug.apk" "%~dp0..\ISHA.apk"
)
