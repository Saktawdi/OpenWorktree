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

!macro NSIS_HOOK_PREUNINSTALL
  ; 先收割可能仍在运行的后端/壳进程，避免 SQLite/克隆文件被占用
  nsExec::Exec 'taskkill /F /T /IM "OpenWorktree.exe"'
  Pop $0
  nsExec::Exec 'taskkill /F /T /IM "ow.exe"'
  Pop $0
  Sleep 500

  ; 重根（工单克隆/项目镜像）在安装目录同级。删除需显式确认；卸载本体不动轻根。
  IfFileExists "$INSTDIR\..\OpenWorktree-data\*.*" 0 owt_no_heavy_data
    MessageBox MB_YESNO|MB_ICONQUESTION "是否同时删除 OpenWorktree 的工单数据？$\n$\n（$INSTDIR\..\OpenWorktree-data —— 工单克隆工作区与项目镜像，可能包含未归档的本地改动，删除后不可恢复）$\n$\n选「是」：删除这些数据。$\n选「否」：保留在磁盘上，重新安装（含换路径安装）后仍可继续使用。" IDYES owt_drop_heavy_data IDNO owt_no_data
  owt_drop_heavy_data:
    RMDir /r "$INSTDIR\..\OpenWorktree-data"
  owt_no_heavy_data:
  owt_no_data:
!macroend

!macro NSIS_HOOK_POSTINSTALL
  ; 无需恢复动作：轻根（系统盘）与重根（安装同级）都不在安装载荷内，重装后原样可用。
!macroend
