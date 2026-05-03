@echo off
REM WASAPI Loopback Native DLL 编译脚本
REM 需要在 Visual Studio Developer Command Prompt 中运行

setlocal

set CPP_DIR=%~dp0
set OUTPUT_DIR=%CPP_DIR%..\resources

REM 查找 JDK include 目录
if defined JAVA_HOME (
    set JDK_INCLUDE=%JAVA_HOME%\include
    set JDK_INCLUDE_WIN32=%JAVA_HOME%\include\win32
) else (
    echo JAVA_HOME not set. Trying to find JDK...
    where javac >nul 2>&1
    if errorlevel 1 (
        echo ERROR: JAVA_HOME not set and javac not in PATH
        exit /b 1
    )
    for /f "tokens=*" %%i in ('where javac') do (
        set JAVAC_PATH=%%i
        goto :found_javac
    )
    :found_javac
    set JDK_DIR=%JAVAC_PATH%\..\..
    set JDK_INCLUDE=%JDK_DIR%\include
    set JDK_INCLUDE_WIN32=%JDK_DIR%\include\win32
)

echo JDK Include: %JDK_INCLUDE%
echo Output Dir: %OUTPUT_DIR%

if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"

echo Compiling wasapi_loopback.dll...
cl.exe /EHsc /MD /O2 /LD ^
    /I"%JDK_INCLUDE%" ^
    /I"%JDK_INCLUDE_WIN32%" ^
    /I"%CPP_DIR%" ^
    "%CPP_DIR%wasapi_loopback.cpp" ^
    /Fe:"%OUTPUT_DIR%\wasapi_loopback.dll" ^
    /link ole32.lib uuid.lib oleaut32.lib advapi32.lib

if errorlevel 1 (
    echo.
    echo ERROR: Compilation failed. Make sure you're running from a
    echo Visual Studio Developer Command Prompt.
    exit /b 1
)

echo.
echo SUCCESS: wasapi_loopback.dll compiled to %OUTPUT_DIR%
echo Copy to src/jvmMain/resources/ for runtime loading.

endlocal
