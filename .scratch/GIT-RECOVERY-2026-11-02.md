# git 对象库修复结果（2026-11-02 收尾）

> 承接 `handoff-git-recovery.md`。战场已清理完毕，**仓库可正常开发**，但历史中段存在不可恢复的缺口（详见 §3）。
> 工作区：`C:\Users\a1368\Desktop\better_school`（Windows + Git Bash）

## 1. 已完成

| 动作 | 结果 |
|---|---|
| 重跑 `pack_extract.py` 解包 | `extracted 5810 + already-present 16913 + bad 70 = 22793`，CRC 预检 0 失败；loose 从 18626 → 22793 |
| 重写票 04 时代坏 blob `a4aa1e9e` | `CommunityRepository.java`，48995 B，`cat-file` 可读 |
| 修复损坏 loose `0e625bdb` | 路径 `.scratch/virtual-thread/measure/samples-vton/t25.txt`，**文件仍在磁盘且哈希完全一致**，已重建（1876676 B）；原坏文件移入隔离区 `corrupt-loose/` |
| 二次救援 `pack_rescue.py` | 对「delta 流完好、仅基对象损坏」的条目，改用仓库现有 loose 库作基对象重建 → **救回 2 个**：`06a643ee`（32326 B）、`a883983e`（48517 B），均为 CommunityRepository.java 历史版本 |
| 隔离区归置 | 坏 pack 三件套 + 坏 loose 全部在 `C:\Users\a1368\Desktop\bs-pack-quarantine\`（未删除） |
| 对象库快照备份 | `C:\Users\a1368\Desktop\bs-git-objects-backup-20261102.tar`（312 MB，24695 个对象文件，含全部救回对象） |

`git fsck --no-dangling` 当前输出：**仅 35 条 missing + 35 条 broken link，0 个损坏 loose，无其他错误**。

## 2. 当前健康状态

- `count-objects -v`：24439 loose / 284 MB / **无 pack**（刻意保持 loose 形态）。
- 8 个分支的当前快照全部完整：master 2092 文件，7 个 prototype 分支 519–964 文件，`git ls-tree -r` 全部 exit 0。
- HEAD `a3feab33`：`git log` / `git show --stat` / `git status` 全部正常；最近 7 个提交（含 `b9069fa`、`85755f2`、`a3feab33`）diff 全部可读。第 8 个 `f060aaee` 自身树完整、但父提交 `25587b7c` 有缺口且 diff 正好撞上，故 `git show` 报 `unable to read tree`——这是 11-01 后唯一受影响的位置。
- 未跟踪项 20 条 = 交接单列明的刻意垃圾（`META-INF/`、`org/`、`inventory-l2w256/`、`loadtesting/`、`review_diff*.txt`、`简历-*.md`、`.scratch` 下 `*.diff` 与 `timeout-inline/probe/`，外加本次留下的两个救援脚本）。

## 3. 残留损坏（不可恢复）

**35 个对象永久丢失**，全部集中在 `bs-app/src/main/.../repository/adapter` 这条链路上，均为早期迭代版本：

- 9 个 blob：`ITradeRepository.java` ×8、`IInventoryRepository.java` ×1
- 26 个 tree：`adapter` ×15、`repository` ×3、`betterschool` ×2、`bs-app`/`src`/`main`/`resources`/`infrastructure`/`send` 各 1

**受影响提交：71 / 341**（master 主干），分布在 4 段：2026-10-16 单点、10-17～10-23（35 个）、10-23～11-01（33 个）、11-01～11-02 边界处 2 个。11-02 当天的提交全部完好。

**为什么救不回来**：这 35 个对象在 pack 内的字节本身就是坏的（zlib 头校验失败），且其 delta 链的根也落在同一批坏对象上；仓库无 remote、硬盘上无第二份副本、其他 4 个本机仓库均不含这些 SHA。已逐条验证，非"漏找"。

## 4. 影响面（务必知情）

| 操作 | 状态 |
|---|---|
| 日常开发（status / add / commit / branch / switch 到 HEAD） | ✅ 正常 |
| `git log`、`git show --stat`（不含坏子树的提交） | ✅ 正常 |
| `git ls-tree -r <坏提交>`、按路径查历史（`git log -- <该文件>`） | ❌ `fatal: unable to read tree` |
| `git checkout <坏提交>`、`git archive` | ❌ 全树遍历会撞缺失 tree |
| **`git push` / `git clone` / `git bundle` / `git gc`** | ❌ 已实测 `git pack-objects --all` → `fatal: bad tree object 11ffc344...`（exit 128） |

最后一条最要紧：**在缺口存在期间，这个库无法被打包，因此无法推送到远程、无法克隆、也无法 gc**。本地单向前进不受影响。

## 5. 建议

1. **不要跑 `git gc` / `git repack` / `git push`**（必然报错，且 gc 在坏库上再做清理风险不可控）。
2. **先做硬件体检**：memtest + `chkdsk /f` + 看 SMART。本次是 IO/内存压力下持续写坏（Docker VM 崩、git 对象坏、python 解压 segfault），软件层修完还会再坏。
3. 历史缺口怎么处理，需要用户拍板（三选一）：
   - **A. 就这样放着**（推荐）：本地继续开发，不 gc、不推远程；代价是上面第 4 节的 ❌ 项永久存在。
   - **B. 切除受损段**：把 71 个受损提交从历史中删掉（内容已丢，删除不额外损失），换来一个完整可打包、可推远程的库；代价是提交数 341 → 270，SHA 全变。
   - **C. 重建新库**：以 8 个分支当前快照为起点开新历史，彻底干净但丢掉全部历史。
4. 硬件体检通过、且选定 B/C 之后，再决定隔离区 `bs-pack-quarantine/` 与备份 tar 是否删除。

## 6. 遗留脚本（`.scratch/`，不入库）

- `pack_extract.py`：按偏移排序解包 + CRC 预检，损坏区直接跳过（避免解坏数据 segfault）。可重复执行。
- `pack_rescue.py`：对 delta 流完好的条目，用 loose 库作基对象重建，SHA 校验通过才落盘。
