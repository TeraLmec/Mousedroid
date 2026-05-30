@echo off
setlocal 

if not exist "mousedroid_win64" mkdir "mousedroid_win64"

xcopy "out\build\x64-Release\bin\*.*" "mousedroid_win64\" /E /I /Y /H

xcopy "adb" "mousedroid_win64\adb\" /E /I /Y

copy /y app.ico mousedroid_win64

(
    echo MINIMIZE_TASKBAR=0
    echo MOVE_SENSITIVITY=10
    echo RUN_STARTUP=0
    echo SCROLL_SENSITIVITY=3 
)>mousedroid_win64\config.ini

echo Deployment to "mousedroid_win64" complete!
pause