@echo off
setlocal

REM 设置 MSVC 环境
call "C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvarsall.bat" x64
if errorlevel 1 (
    echo ERROR: Failed to set up MSVC environment
    exit /b 1
)

set CPP_DIR=D:\AndroidStudioProjects\MicYou\composeApp\src\jvmMain\cpp
set OUTPUT_DIR=D:\AndroidStudioProjects\MicYou\composeApp\src\jvmMain\resources
set JDK_INCLUDE=C:\Program Files\Java\jdk-21\include
set JDK_WIN32=C:\Program Files\Java\jdk-21\include\win32

if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"

echo.
echo Compiling wasapi_loopback.dll...
echo CPP_DIR: %CPP_DIR%
echo JDK_INCLUDE: %JDK_INCLUDE%
echo OUTPUT: %OUTPUT_DIR%
echo.

cl.exe /EHsc /MD /O2 /LD ^
    /I"%JDK_INCLUDE%" ^
    /I"%JDK_WIN32%" ^
    /I"%CPP_DIR%" ^
    "%CPP_DIR%\wasapi_loopback.cpp" ^
    /Fe:"%OUTPUT_DIR%\wasapi_loopback.dll" ^
    /link ole32.lib uuid.lib oleaut32.lib advapi32.lib

if errorlevel 1 (
    echo.
    echo COMPILATION FAILED
    exit /b 1
)

echo.
echo SUCCESS: wasapi_loopback.dll compiled
dir "%OUTPUT_DIR%\wasapi_loopback.dll"

endlocal
