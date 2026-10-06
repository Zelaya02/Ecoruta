@echo off
echo ========================================
echo Compilando proyecto Ruteo Inteligente
echo ========================================
echo.

REM Mostrar directorio actual
echo Directorio actual: %CD%
echo.

REM Verificar que existe Main.java
if not exist "src\main\java\com\ruteo\Main.java" (
    echo ERROR: No se encuentra src\main\java\com\ruteo\Main.java
    echo.
    echo Verifica que la estructura de carpetas sea:
    echo   backend\
    echo     src\
    echo       main\
    echo         java\
    echo           com\
    echo             ruteo\
    echo               Main.java
    pause
    exit /b 1
)

echo ✅ Main.java encontrado
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

REM Configurar variables de entorno por defecto
if "%DB_PASSWORD%"=="" set "DB_PASSWORD=Zelaya11"
if "%DB_USER%"=="" set "DB_USER=postgres"
echo ✅ Credenciales DB: Usuario=%DB_USER% | Password configurada
echo.


REM Crear carpetas necesarias
echo Creando carpetas...
if not exist "target\classes" mkdir "target\classes"
if not exist "lib" mkdir "lib"
echo ✅ Carpetas creadas
echo.

REM Descargar dependencias
echo Descargando dependencias...
echo.

if not exist "lib\postgresql-42.6.0.jar" (
    echo Descargando PostgreSQL driver...
    powershell -Command "& {[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://jdbc.postgresql.org/download/postgresql-42.6.0.jar' -OutFile 'lib\postgresql-42.6.0.jar'}"
    echo ✅ PostgreSQL driver descargado
) else (
    echo ✅ PostgreSQL driver ya existe
)

if not exist "lib\gson-2.10.1.jar" (
    echo Descargando Gson...
    powershell -Command "& {[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar' -OutFile 'lib\gson-2.10.1.jar'}"
    echo ✅ Gson descargado
) else (
    echo ✅ Gson ya existe
)

if not exist "lib\jbcrypt-0.4.jar" (
    echo Descargando jBCrypt...
    powershell -Command "& {[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/org/mindrot/jbcrypt/0.4/jbcrypt-0.4.jar' -OutFile 'lib\jbcrypt-0.4.jar'}"
    echo ✅ jBCrypt descargado
) else (
    echo ✅ jBCrypt ya existe
)

echo.
echo Compilando Java files...
javac -encoding UTF-8 -cp "lib/*" -d target/classes src\main\java\com\ruteo\model\Usuario.java src\main\java\com\ruteo\repository\UsuarioRepository.java src\main\java\com\ruteo\Main.java

if %errorlevel% neq 0 (
    echo.
    echo ERROR: La compilacion fallo
    echo Revisa que el codigo de Main.java no tenga errores
    pause
    exit /b %errorlevel%
)

echo.
echo ========================================
echo ✅ Compilacion exitosa!
echo ========================================
echo.
echo Configurando API keys...
set ORS_API_KEY=eyJvcmciOiI1YjNjZTM1OTc4NTExMTAwMDFjZjYyNDgiLCJpZCI6ImE2Y2NjNjBiOTNiYjRlMTZiNmY2MDQxZGI3NWYyZTljIiwiaCI6Im11cm11cjY0In0=
echo ✅ ORS_API_KEY configurada
echo.
echo Ejecutando servidor...
echo ========================================
echo.
java -cp "target/classes;lib/*" com.ruteo.Main

pause