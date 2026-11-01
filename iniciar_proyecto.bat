@echo off
setlocal enabledelayedexpansion
title EcoRuta - Iniciador del Sistema

echo ===================================================
echo       Iniciando Sistema Completo EcoRuta
echo ===================================================
echo.

REM 1. Localizar directorio raíz
cd /d "%~dp0"
set "ROOT_DIR=%CD%"
set "PG_DIR=%ROOT_DIR%\..\pgsql"
set "BACKEND_DIR=%ROOT_DIR%\backend"

REM 2. Detectar JDK
set "JAVA_HOME="
if exist "C:\Program Files\Eclipse Adoptium" (
    for /d %%d in ("C:\Program Files\Eclipse Adoptium\jdk-*") do (
        if exist "%%d\bin\javac.exe" set "JAVA_HOME=%%d"
    )
)
if not defined JAVA_HOME (
    if exist "%ProgramFiles%\Java" (
        for /d %%d in ("%ProgramFiles%\Java\jdk-*") do (
            if exist "%%d\bin\javac.exe" set "JAVA_HOME=%%d"
        )
    )
)
if defined JAVA_HOME (
    set "PATH=%JAVA_HOME%\bin;%PATH%"
    echo [OK] JDK detectado: %JAVA_HOME%
) else (
    echo [INFO] Usando Java del PATH del sistema.
)

REM 3. Iniciar Base de Datos PostgreSQL si no esta activa en puerto 5432
echo.
echo [1/3] Verificando Base de Datos PostgreSQL...
powershell -Command "if (Get-NetTCPConnection -LocalPort 5432 -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }"
if %errorlevel% equ 0 (
    echo [OK] PostgreSQL ya se encuentra activo en el puerto 5432.
) else (
    echo [INFO] PostgreSQL no esta activo. Iniciando cluster local...
    if exist "%PG_DIR%\pgsql\bin\postgres.exe" (
        if exist "%PG_DIR%\data\postmaster.pid" (
            del /f /q "%PG_DIR%\data\postmaster.pid" >nul 2>&1
        )
        start /b "" "%PG_DIR%\pgsql\bin\postgres.exe" -D "%PG_DIR%\data" >nul 2>&1
        timeout /t 3 /nobreak >nul
        echo [OK] PostgreSQL iniciado correctamente.
    ) else (
        echo [ADVERTENCIA] No se encontro la carpeta portable en %PG_DIR%. Verifica que tu servicio PostgreSQL este iniciado.
    )
)

REM 4. Configurar Variables de Entorno del Backend
set "DB_USER=postgres"
if "%DB_PASSWORD%"=="" set "DB_PASSWORD=Zelaya11"
set "DB_NAME=ruteo_db"
set "ORS_API_KEY=eyJvcmciOiI1YjNjZTM1OTc4NTExMTAwMDFjZjYyNDgiLCJpZCI6ImE2Y2NjNjBiOTNiYjRlMTZiNmY2MDQxZGI3NWYyZTljIiwiaCI6Im11cm11cjY0In0="

REM 5. Compilar si faltan las clases
echo.
echo [2/3] Verificando compilacion del Backend...
cd /d "%BACKEND_DIR%"
if not exist "target\classes\com\ruteo\Main.class" (
    echo [INFO] Compilando codigo Java...
    if not exist "target\classes" mkdir "target\classes"
    javac -encoding UTF-8 -cp "lib/*" -d target/classes src\main\java\com\ruteo\model\Usuario.java src\main\java\com\ruteo\repository\UsuarioRepository.java src\main\java\com\ruteo\Main.java
    if errorlevel 1 (
        echo [ERROR] La compilacion de Java ha fallado.
        pause
        exit /b 1
    )
    echo [OK] Backend compilado exitosamente.
) else (
    echo [OK] Clases compiladas disponibles.
)

REM 6. Abrir Navegador Web en segundo plano
echo.
echo [3/3] Iniciando Servidor Web y Abriendo Frontend...
start "" powershell -Command "Start-Sleep -Seconds 2; Start-Process 'http://localhost:8080/login1.html'"

echo.
echo ===================================================
echo  EcoRuta esta en linea en: http://localhost:8080/login1.html
echo  Para detener el servidor, cierra esta ventana (Ctrl+C).
echo ===================================================
echo.

java -cp "target/classes;lib/*" com.ruteo.Main

pause
