# Intentprobe

独立 APK，用于探测目标应用的 Activity Intent 与静态路由，产出可复制的 `intent:` URI、纯 URI 或完整类名，供手动粘贴到 `launchRedirector`。

包含两部分：
1. **探测器 App**（`com.example.intentprobe`）：扫描已安装应用、收集已探测应用发出的 Intent、显示路由候选、测试启动、导出规则。
2. **Xposed 模块**（同一 APK 内）：在 LSPosed 作用域中勾选的目标进程里 hook `Instrumentation.execStartActivity`、`Activity.onCreate`、`Activity.onNewIntent`，把捕获的 Intent 通过自有 `ContentProvider` 发回探测器存储。

## 核心特性

- **深度捕获数据通路**：目标进程 hook → 探测器自有 `ContentProvider`（`com.example.intentprobe.provider`）→ App 存储；按批 flush；provider 校验调用方 uid 对应的包名是否在探测列表。
- **双路静态候选**：manifest 导出路由 + dex 字符串扫描（过滤 `_$$Fragment`、资源后缀、http/https，上限 2000，`origin="dex"`）。普通 manifest 路由和 dex 路由均可“测试启动”并复制规则。
- **测试启动**：与导出共用 `RuleExporter.buildLaunchIntent(...)`，批量 resolve + 独立 `route_tests` 表，单次手动启动 + 捕获关联。解析只处理 `origin=dex` 且 uri 非空的路由；先 `ACTION_VIEW + setPackage`，再用同 scheme 的 manifest action 重试；用 `queryIntentActivities(..., MATCH_DEFAULT_ONLY)` 并过滤目标包。
- **导出规则**：`intent:` URI、`纯 URI`（仅 `canUsePureUri` 且 action 为空或 VIEW）、`完整类名`。敏感 extras 默认只存 key + 类型，不存值。
- **安全**：不引用 `launchRedirector` 的类、资源或规则文件；不修改现有 `launchRedirector` 代码；测试启动禁用系统应用与自身包；高风险关键词（pay/order/logout/delete 等）需二次确认；锁定 Activity 需单独风险确认。
- **LSPosed**：`xposedscope` 留空，用户在 LSPosed 中按需勾选待探测应用，强行停止并重新打开应用后生效。
- **minSdk 29**；不支持工作资料/多用户。
- **状态栏/导航栏**：已做 insets 修复，不与系统栏重叠；已开启探测的应用在列表中排最上方。

## 项目结构

```
app/src/main/java/com/example/intentprobe/
├── MainActivity.java            # 应用列表、搜索、探测开关
├── AppDetailActivity.java       # 应用详情、路由列表、测试启动、导出
├── capture/
│   ├── ProbeHook.java           # Xposed 入口：hook execStartActivity / onCreate / onNewIntent
│   ├── ProbeClient.java         # 向 ContentProvider 发送捕获事件与心跳
│   └── ProbeProtocol.java       # 协议格式：JSON extras、敏感 key 屏蔽、心跳间隔
├── data/
│   ├── ProbeDatabase.java       # SQLiteOpenHelper，DB v3，route_tests 表
│   ├── DbContract.java          # 表/列常量
│   ├── ProbeRepository.java     # 路由/捕获/测试结果读写
│   └── ProbeProvider.java       # 自有 ContentProvider，跨进程接收 capture
├── export/
│   └── RuleExporter.java        # intent: URI / 纯 URI / 类名 构造与校验
├── model/
│   ├── AppEntry.java
│   ├── CaptureEntry.java
│   ├── ConnectionEntry.java
│   └── RouteCandidate.java      # 路由字段、resolve/test 状态、routeKey()
├── route/
│   ├── RouteResolver.java       # dex 路由批量解析（ACTION_VIEW + manifest action 回退）
│   └── RouteTester.java         # 单次测试启动，高风险判断
├── scan/
│   ├── ApkManifestScanner.java  # manifest 解析（getManifestXml() + DOM，不设 JAXP hardening）
│   ├── DexRouteScanner.java     # classes*.dex 流式扫描 scheme 常量
│   └── AppListLoader.java       # 已安装应用列表
└── ui/
    ├── AppAdapter.java
    ├── RouteAdapter.java        # dex 徽标、解析/测试状态展示、搜索过滤
    ├── CaptureAdapter.java
    ├── WindowInsetsHelper.java  # 系统栏/刘海 insets
    └── MaxHeightScrollView.java # 对话框可滚动容器
```

## 用法

### 1. 安装与 LSPosed 配置

将 APK 安装到设备，在 LSPosed Manager 中启用 Intentprobe 模块，在“模块 → Intentprobe → 作用域”中勾选想要探测的目标应用，然后**强行停止并重新打开**目标应用。

`xposedscope` 已留空，作用域完全由 LSPosed 界面控制，不针对具体应用硬编码。

### 2. 主界面

打开 Intentprobe 后显示已安装应用列表，已开启探测的应用排在最上方，带“已开启探测 · 等待连接”标记。点击应用进入详情页；右侧开关可打开/关闭该应用的探测。

### 3. 应用详情页

详情页顶部状态卡**默认收起**，只保留按钮、连接状态与最近心跳摘要；点击展开箭头可查看包名、进程和完整的 LSPosed 引导。

- **静态候选** 标签页：列出 manifest 与 dex 路由，dex 路由带 `dex · 可启动/无匹配/已阻止` 徽标，支持搜索过滤（路由、Activity、scheme、解析组件、测试组件）。
- 点击某条路由弹出选项对话框，可：
  - **测试启动**：用与导出相同的 Intent 启动一次，区分已启动/未匹配/未导出/安全失败/启动失败；高风险路由需二次确认；30 秒内尝试与目标进程捕获关联（时间窗口 + 组件匹配的推断）。
  - **复制 intent: URI（推荐）**：可直接粘贴到 launchRedirector。
  - **复制纯 URI**：仅在可用纯 URI 且 action 为空或 VIEW 时提供。
  - **复制类名**：锁定 Activity，需单独风险确认。
  - **复制详情**：包名、组件、来源、解析/测试状态等文本。

- **捕获记录** 标签页：显示目标进程 hook 到的 Intent，带源（onCreate/onNewIntent/outgoing）与敏感 extras 屏蔽标记，同样可复制规则。

### 4. 导出规则示例

```
intent:#Intent;action=android.intent.action.VIEW;data=smobagamehelper://about;package=com.tencent.gamehelper.smoba;end
```

纯 URI（无 component、action 为 VIEW）：
```
smobagamehelper://about
```

完整类名（锁定 Activity）：
```
com.tencent.gamehelper.smoba.ui.MediatorRouterActivity
```

## 构建

```bash
.\gradlew.bat :app:assembleDebug --no-daemon
```

构建超时设为 300 秒。生成的 APK 在 `app/build/outputs/apk/debug/app-debug.apk`。

安装：
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 限制

- 不支持工作资料/多用户。
- 测试启动与捕获关联属于单次手动操作，不执行自动批量启动。
- 捕获关联只能做 `startedAt - 500ms` 时间窗口 + 组件匹配的“推断”，因为 P2 `capture()` 不传 caller。
- dex 路由解析是“可启动”的静态推断，实际落地页可能是中转页，仍需用户确认。

## 数据库版本

- v1：初始（captures, routes, apps）。
- v2：新增 `captures.target_pkg`、`captures.caller`、`routes.origin`（默认 `manifest`）。
- v3：新增 `route_tests` 表（`pkg`, `route_key`, `resolve_state`, `resolved_component`, `resolved_action`, `test_result`, `tested_at`, `tested_component`），APK 版本或修改时间变化时清空该应用的测试结果。
