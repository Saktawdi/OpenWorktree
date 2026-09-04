@echo off
rem Build sidecar-jvm-shim. rustc MSVC auto-detection falls back to Git Bash GNU link on
rem this machine and vcvars64.bat is broken, so point at the real link.exe explicitly
rem (see .cargo/config.toml) and set the SDK lib path manually. Keep this file ASCII-only.
set "VCTOOLS=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Tools\MSVC\14.44.35207"
set "SDK=C:\Program Files (x86)\Windows Kits\10\Lib\10.0.22621.0"
set "LIB=%VCTOOLS%\lib\x64;%SDK%\ucrt\x64;%SDK%\um\x64"
cd /d "%~dp0"
cargo build --release
