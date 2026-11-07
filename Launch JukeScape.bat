@echo off
rem Builds JukeScape and launches RuneLite in developer mode with it and the YouTube add-on loaded.
title JukeScape dev

rem The default java on this PC is a JRE, which can't compile the plugin
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot"
if not exist "%JAVA_HOME%\bin\javac.exe" (
	echo Couldn't find a JDK at %JAVA_HOME%
	echo Edit JAVA_HOME at the top of this file to point at a JDK 11 or newer.
	pause
	exit /b 1
)

cd /d "%~dp0"
call "%~dp0gradlew.bat" run
if errorlevel 1 pause
