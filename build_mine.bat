@echo off
set JAVA_HOME=C:\Program Files\Java\jdk-17.0.12
set ANDROID_HOME=C:\Users\guest\AppData\Local\Android\Sdk
cd /d A:\Dev\MyAgent
if exist build_mine.log del build_mine.log
echo === START %DATE% %TIME% === > build_mine.log
call gradlew.bat :app:assembleDebug --console=plain < nul >> build_mine.log 2>&1
echo EXITCODE=%ERRORLEVEL% >> build_mine.log
echo === DONE %DATE% %TIME% === >> build_mine.log
