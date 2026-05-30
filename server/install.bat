@echo off
setlocal

if "%VCPKG_ROOT%"=="" (
    echo VCPKG_ROOT is not set. Set it to your vcpkg folder and retry.
    exit /b 1
)

set TRIPLET=x64-windows
if not "%~1"=="" set TRIPLET=%~1

set DEST=%~dp0mousedroid_win64
if not exist "%DEST%" mkdir "%DEST%"

xcopy "%VCPKG_ROOT%\installed\%TRIPLET%\bin\*.dll" "%DEST%\" /E /I /Y /H

echo Copied vcpkg DLLs to "%DEST%".
