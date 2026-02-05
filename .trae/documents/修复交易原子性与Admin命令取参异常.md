## 目标
- 修复“转账失败但继续发货/扣物品”的真实风险（商店 buy/sell 分支）。
- 修复 `AccountCommandAdmin.process()` 可在缺少 amount 时被调用导致的运行期取参异常。
- 对我此前改动做一次针对性复核，确保没有引入新问题或过度防御导致行为异常。

## 变更点（将修改的文件）
### 1) 商店交易：只在转账成功后继续处理物品
- 文件： [EventHandler.java](file:///c:/porjecket/我的世界模组/DiceMC-Money-Mod_For_pigup/src/main/java/dicemc/money/event/EventHandler.java)
- 位置：`processTransaction(...)` 的 `buy` 与 `sell` 分支（你刚刚看的区间，buy 在约 L351-L404，sell 在约 L406-L489）。
- 改动策略：
  - 将 `wsd.transferFunds(...)` 的返回值保存为 `boolean 成功`。
  - 若失败：立即给玩家发失败提示（复用现有翻译 key，例如 `message.command.transfer.failure`），并 `return`；不执行任何物品扣除/发放，也不写历史。
  - 若成功：再写历史、再进行实际 `extractItem(false)` / `removeItem` / `insertItem(false)`。

## 2) Admin 命令：禁止无 amount 的执行路径（或在 process 内安全降级）
- 文件： [AccountCommandAdmin.java](file:///c:/porjecket/我的世界模组/DiceMC-Money-Mod_For_pigup/src/main/java/dicemc/money/commands/AccountCommandAdmin.java)
- 现状：注册树允许在 `action + player` 后直接 `.executes(process)`，但 `process()` 无条件读取 `amount`。
- 改动策略（最小风险方案）：
  - 在 `register()` 里移除两条无 amount 的 `.executes(process)`（byName 分支与 online 分支各一处）。
  - 仅保留带 `amount` 参数的执行路径（`... player -> amount -> executes(process)`）。
  - 这样不会改变 `process()` 主要逻辑，只是避免无效命令输入触发异常与日志噪声。

## 3) 代码复核（针对我之前改动）
- 重点复核点：
  - `MoneyWSD`：同步范围是否过大、`getAccountMap()` 返回快照是否会影响现有调用（目前仅 /top 使用）、`save()` 与加载路径是否一致。
  - `DatabaseManager`：关闭时机与 `postEntry()` 关闭态行为（已做 `isClosed()` 守卫）是否会产生副作用。
  - 命令层：ProfileCache 降级逻辑是否会把“玩家不存在”误判为 UUID 字符串输出。
- 复核方式：
  - 改完后跑一次 IDE 诊断检查（确保无新增编译/静态错误）。
  - 不强制跑完整构建（你已说明编译问题可省略），若你希望我也可以在确认后顺手跑一次 build 作为额外保障。

## 验收标准
- 商店 buy/sell：在任何 `transferFunds()` 返回 false 的情况下，不会发货/扣物品/写历史。
- admin byName/online：不带 amount 的命令不再进入 `process()`，不会出现运行期取参异常。
- IDE 诊断为 0，且变更不影响现有正常交易/命令流程。