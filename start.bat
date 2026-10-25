@echo off
REM TradeAgent backend launcher: IoT MQTT + Kafka + Feishu live mode
REM Secrets live in secrets-local.bat (git-ignored). Copy secrets-local.bat.example and fill in.

cd /d "%~dp0"
if not exist secrets-local.bat (
    echo [ERROR] secrets-local.bat not found.
    echo Copy secrets-local.bat.example to secrets-local.bat and fill in your credentials.
    pause
    exit /b 1
)
call secrets-local.bat

cd /d "%~dp0server"

echo [1/3] Stopping old java (avoid jar lock)...
taskkill /f /im java.exe >nul 2>&1
ping -n 4 127.0.0.1 >nul

echo [2/3] Building (skip tests)...
call mvn -q -DskipTests clean package
if errorlevel 1 (
    echo BUILD FAILED - see maven output above
    pause
    exit /b 1
)

echo [3/3] Starting backend (MQTT+Kafka+Feishu all-live)...
java "-Dfile.encoding=UTF-8" ^
  "-Diot.mqtt.enabled=true" ^
  "-Diot.kafka.enabled=true" ^
  "-Dspring.kafka.bootstrap-servers=127.0.0.1:9092" ^
  "-Dspring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer" ^
  "-Dspring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer" ^
  "-Dfeishu.app-id=%FEISHU_APP_ID%" ^
  "-Dfeishu.app-secret=%FEISHU_APP_SECRET%" ^
  "-Dfeishu.iot-alert-webhook-url=%FEISHU_WEBHOOK%" ^
  "-Dfeishu.bitable-app-token=%FEISHU_BITABLE_TOKEN%" ^
  "-Dfeishu.bitable-table-id=%FEISHU_BITABLE_TABLE%" ^
  "-Dfeishu.bitable-poll-enabled=true" ^
  "-Dspring.profiles.active=local" ^
  -jar target/tradeagent-server-1.0.0.jar

pause
