; OpenWorktree NSIS 安装器钩子（tauri.conf.json → bundle.windows.nsis.installerHooks）。
;
; 数据目录默认在安装目录 data\ 下（gate.toml、工单数据库、令牌与工单克隆——克隆体积会
; 持续增长，数据随安装盘走）。卸载时用户二选一：
;   保留 → data\ 整体搬到 %APPDATA%\OpenWorktree\data（跨卷 rename 失败则复制）；
;   删除 → 留在原地随卸载清理，并显式 RMDir 兜底。
; 重装时 POSTINSTALL 钩子把保留的数据搬回新安装目录；壳启动逻辑（src-tauri/src/lib.rs
; resolve_data_dir）兜底接管 %APPDATA%\OpenWorktree\data 与旧版 Roaming 数据的搬迁。
!macro NSIS_HOOK_PREUNINSTALL
  ; 先收割可能仍在运行的后端/壳进程，避免 SQLite/克隆文件被占用导致搬移或删除失败
  nsExec::Exec 'taskkill /F /T /IM "OpenWorktree.exe"'
  Pop $0
  nsExec::Exec 'taskkill /F /T /IM "ow.exe"'
  Pop $0
  Sleep 500

  IfFileExists "$INSTDIR\data\local-run\*.*" 0 owt_no_data
    MessageBox MB_YESNO|MB_ICONQUESTION "是否保留 OpenWorktree 的本地数据？$\n$\n（gate.toml 配置、工单数据库、令牌与工单克隆）$\n$\n选「是」：数据移动到 $APPDATA\OpenWorktree\data，重新安装时自动恢复。$\n选「否」：数据随卸载一并删除。" IDYES owt_keep_data IDNO owt_drop_data
  owt_keep_data:
    CreateDirectory "$APPDATA\OpenWorktree"
    Rename "$INSTDIR\data" "$APPDATA\OpenWorktree\data"
    IfFileExists "$INSTDIR\data\local-run\*.*" 0 owt_no_data
      ; 跨卷或目标已存在导致 rename 失败：退回复制内容（源目录随后随卸载清理）
      CopyFiles /SILENT "$INSTDIR\data\local-run\*.*" "$APPDATA\OpenWorktree\data\local-run"
      Goto owt_no_data
  owt_drop_data:
    RMDir /r "$INSTDIR\data"
  owt_no_data:
!macroend

!macro NSIS_HOOK_POSTINSTALL
  ; 上次卸载选择保留的数据：搬回本次安装目录。
  ; rename 失败（目标已存在/跨卷）时数据留在 %APPDATA%\OpenWorktree\data，由壳启动
  ; 逻辑（resolve_data_dir）兜底接管。
  IfFileExists "$APPDATA\OpenWorktree\data\local-run\*.*" 0 owt_restore_done
    Rename "$APPDATA\OpenWorktree\data" "$INSTDIR\data"
  owt_restore_done:
!macroend
