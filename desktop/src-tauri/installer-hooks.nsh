; OpenWorktree NSIS 安装器钩子（tauri.conf.json → bundle.windows.nsis.installerHooks）。
;
; 数据布局（分根，v1）：
;   · 轻根 %APPDATA%\OpenWorktree\local-run —— 小数据/配置（gate.toml、gate.db、令牌、
;     审计等），常驻系统盘每用户目录。卸载/重装/在线热更新【永不触碰】；
;   · 重根 <安装目录同级>\OpenWorktree-data —— 工单克隆工作区与项目镜像 auth-*.git
;     （node_modules 级大体积），随安装盘放置。重根在安装包载荷【之外】：
;       重装同路径 → 重根原样保留并继续使用；
;       覆盖安装 / 在线热更新 → 载荷只写安装目录，重根不受影响；
;   · 快速模式超级工单工作区 = 用户项目路径，本就不在安装器管辖内。
; 因此卸载器不再搬移/备份任何数据：唯一的数据销毁动作是下方显式询问后删除重根；
; 轻根属每用户数据，卸载器一律不碰（重装后自动回到同一份数据）。
;
; 进程收割：安装目录里有两个要杀的进程 —— 壳 OpenWorktree.exe 与 sidecar 后端 ow.exe。
; Tauri 内建的「应用运行中」检测只认主程序，壳被强杀时走不到自家 ExitRequested 的
; 收割兜底，后端 ow.exe 会留成孤儿：继续占 18080 并锁定自身文件，随后复制 ow.exe 即弹
; 「Error opening file for writing」。三层防御：后端有 stdin EOF 自退守护
; （OW_PARENT_WATCHDOG，见 GateWebApp#startShellDeathWatch），壳死即退；这里再在
; 文件落盘前统一强杀并轮询等待进程真正退场（两轮兜底）；真有杀不掉的仍放行，
; 由 NSIS 原生「Error opening file for writing」重试对话框接管（同旧行为）。

; 杀两类进程并轮询等待退场。prefix 用于生成各插入点独享的标签（NSIS 标签随
; 宏展开复制，不同 Section 内需防重名）。寄存器占用 $0/$1/$2，仅限本宏内部。
!macro OWT_KillAndWait prefix
  StrCpy $2 0
  ${prefix}_round:
    nsExec::Exec 'taskkill /F /T /IM "OpenWorktree.exe"'
    Pop $1
    nsExec::Exec 'taskkill /F /T /IM "ow.exe"'
    Pop $1
    StrCpy $0 0
  ${prefix}_poll:
    ; find 命中（进程还在）→ 退出码 0；未命中（已退场）→ 非 0。
    nsExec::Exec 'cmd /c tasklist /FI "IMAGENAME eq OpenWorktree.exe" /NH | find /I "OpenWorktree.exe"'
    Pop $1
    IntCmp $1 0 0 ${prefix}_poll2 ${prefix}_poll2
      Goto ${prefix}_nap
  ${prefix}_poll2:
    nsExec::Exec 'cmd /c tasklist /FI "IMAGENAME eq ow.exe" /NH | find /I "ow.exe"'
    Pop $1
    IntCmp $1 0 0 ${prefix}_done ${prefix}_done
      Goto ${prefix}_nap
  ${prefix}_nap:
    Sleep 400
    IntOp $0 $0 + 1
    ; 每轮最多等 ~5s，超时再补杀一次；两轮后仍不死则放行。
    IntCmp $0 12 ${prefix}_round2 ${prefix}_poll ${prefix}_round2
  ${prefix}_round2:
    IntOp $2 $2 + 1
    IntCmp $2 1 ${prefix}_round ${prefix}_round ${prefix}_done
  ${prefix}_done:
!macroend

!macro NSIS_HOOK_PREINSTALL
  ; 覆盖安装/升级：文件落盘前收割壳与后端，等待退场后再继续。
  !insertmacro OWT_KillAndWait "owtPI"
!macroend

!macro NSIS_HOOK_POSTINSTALL
  ; 无需恢复动作：轻根（系统盘）与重根（安装同级）都不在安装载荷内，重装后原样可用。
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  ; 先收割可能仍在运行的后端/壳进程，避免 SQLite/克隆文件被占用；等待真正退场。
  !insertmacro OWT_KillAndWait "owtUN"

  ; 重根（工单克隆/项目镜像）在安装目录同级。删除需显式确认；卸载本体不动轻根。
  IfFileExists "$INSTDIR\..\OpenWorktree-data\*.*" 0 owt_no_heavy_data
    MessageBox MB_YESNO|MB_ICONQUESTION "是否同时删除 OpenWorktree 的工单数据？$\n$\n（$INSTDIR\..\OpenWorktree-data —— 工单克隆工作区与项目镜像，可能包含未归档的本地改动，删除后不可恢复）$\n$\n选「是」：删除这些数据。$\n选「否」：保留在磁盘上，重新安装（含换路径安装）后仍可继续使用。" IDYES owt_drop_heavy_data IDNO owt_no_data
  owt_drop_heavy_data:
    RMDir /r "$INSTDIR\..\OpenWorktree-data"
  owt_no_heavy_data:
  owt_no_data:
!macroend
