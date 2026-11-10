@echo off
schtasks /delete /tn rikkabuildM /f >nul 2>&1
schtasks /create /tn rikkabuildM /tr "cmd /c A:\Dev\MyAgent\build_mine.bat" /sc once /st 23:59 /f
schtasks /run /tn rikkabuildM
