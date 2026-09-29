@echo off
echo ========================================
echo Inicializando Base de Datos NEXO
echo ========================================
echo.

REM ------ Auto-detectar JDK instalado (fallback a PATH del sistema) ------
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
    echo ✅ JDK detectado: %JAVA_HOME%
) else (
    echo ⚠️  No se encontro un JDK instalado. Se usara javac/java del PATH.
)
echo.

REM Configurar variables de entorno (cambiar segun sea necesario)
if "%DB_PASSWORD%"=="" (
    echo ⚠️  Variable DB_PASSWORD no definida.
    echo    Configure antes de ejecutar:
    echo    set DB_PASSWORD=su_contraseña
    echo    set DB_USER=postgres
    echo.
)

REM Verificar driver
if not exist "lib\postgresql-42.6.0.jar" (
    echo ERROR: No se encuentra el driver de PostgreSQL en lib/
    pause
    exit /b 1
)

REM Compilar
echo Compilando script de creacion...
javac -encoding UTF-8 -cp "lib/*" CreacionDB.java
if %errorlevel% neq 0 (
    echo ERROR en la compilacion.
    pause
    exit /b 1
)

REM Ejecutar
echo Ejecutando script...
java -cp ".;lib/*" CreacionDB
if %errorlevel% neq 0 (
    echo ERROR en la ejecucion.
    pause
    exit /b 1
)

echo.
echo ========================================
echo ✅ Proceso finalizado correctamente
echo ========================================
pause
