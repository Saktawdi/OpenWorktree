; OpenWorktree NSIS 安装器钩子（tauri.conf.json → bundle.windows.nsis.installerHooks）。
;
; 数据目录默认在安装目录 data\ 下（gate.toml、工单数据库、令牌与工单克隆——克隆体积会
; 持续增长，数据随安装盘走）。卸载时用户二选一：
;   保留 → data\ 搬到 %APPDATA%\OpenWorktree\data（同卷一步 Rename；跨卷/目标已存在则
;          robocopy /E /MOVE 递归并入，同名以本次数据为准）。彻底失败时【不删源】，留在
;          安装目录的残留由壳启动逻辑 data_dir_redirect 按活度/配置指向取舍；
;   删除 → 留在原地随卸载清理，并显式 RMDir /r 兜底。
; 重装时 POSTINSTALL 钩子把保留的数据搬回新安装目录。注意：安装目录若已存在 data\
; （更晚的活数据或上次残留快照）则【不覆盖】——直接覆盖会把更新的活数据退级成旧备份；
; 若搬到的新目录数据其实是旧快照（其 gate.toml 仍指向旧版每用户目录等外部位置），
; 壳启动逻辑会把运行时重定向回活体目录、归档快照并回锚配置（见 lib.rs data_dir_redirect）。
;
; 原则：钩子只做“无冲突搬移”；凡有冲突（目标已存在、跨卷失败）一律留给壳兜底，绝不
; 先删可能更新的数据再搬。

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
    ; 同卷整目录改名最快；目标已存在（上次保留备份）或跨卷时改名失败 → robocopy /MOVE
    ; 内容级并入旧备份（同名以本次为准），绝不先删旧备份再搬
    Rename "$INSTDIR\data" "$APPDATA\OpenWorktree\data"
    IfFileExists "$INSTDIR\data\*.*" 0 owt_no_data
      nsExec::ExecToLog 'cmd.exe /c robocopy "$INSTDIR\data" "$APPDATA\OpenWorktree\data" /E /MOVE /R:1 /W:1 /NFL /NDL /NJH /NJS /NP'
      Pop $0
      ; 仍有残留说明搬移彻底失败：不删源（数据仍在原处），壳启动接管时按活度取舍
      Goto owt_no_data
  owt_drop_data:
    RMDir /r "$INSTDIR\data"
  owt_no_data:
!macroend

!macro NSIS_HOOK_POSTINSTALL
  ; 上次卸载选择保留的数据：搬回本次安装目录。
  ; 安装目录已存在 data\（更晚的活数据/残留快照）时不覆盖，交由壳启动逻辑按库活度
  ; 与配置指向决定运行时归属（data_dir_redirect），避免把更新的数据退级成旧备份。
  IfFileExists "$APPDATA\OpenWorktree\data\local-run\*.*" 0 owt_restore_done
    IfFileExists "$INSTDIR\data\*.*" 0 owt_restore_rename
      Goto owt_restore_done
    owt_restore_rename:
    ; 同卷一步改名；跨卷则 robocopy /MOVE。搬失败数据留在 %APPDATA%\OpenWorktree\data，
    ; 由壳启动逻辑（resolve_data_dir 保留数据兜底 / data_dir_redirect）接管。
    Rename "$APPDATA\OpenWorktree\data" "$INSTDIR\data"
    IfFileExists "$APPDATA\OpenWorktree\data\*.*" 0 owt_restore_cleanup
      nsExec::ExecToLog 'cmd.exe /c robocopy "$APPDATA\OpenWorktree\data" "$INSTDIR\data" /E /MOVE /R:1 /W:1 /NFL /NDL /NJH /NJS /NP'
      Pop $0
    owt_restore_cleanup:
    ; 搬空后清掉可能残留的空壳目录（RMDir 不带 /r 只删空目录，安全）
    RMDir "$APPDATA\OpenWorktree\data"
  owt_restore_done:
!macroend
