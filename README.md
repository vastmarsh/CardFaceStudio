# 卡面工坊（CardFaceStudio）

> **适用范围：仅针对 OPPO 钱包（`com.finshell.wallet`）的卡面替换。**
> 其它 App 的卡面目录结构、图片命名与加载逻辑各不相同，本项目未做适配，也不在支持范围内。

一个用于替换 **OPPO 钱包卡面图片** 的小工具：通过 Shizuku 获得特权身份，读取钱包目录里的卡面，选中一张后用自定义图片替换（同名覆盖，先备份），随时可一键复原。

- 语言：Kotlin
- 架构：纯 Kotlin/JVM，无 native 代码，因此 **arm64-v8a / armeabi-v7a 通吃**（APK 本身是通用架构）
- minSdk 26（Android 8.0），targetSdk 34
- 包名：`com.cardface.studio`

---

## 一、它解决什么问题

OPPO 钱包的卡面素材存放在应用外部私有目录中，普通 App 受分区存储限制无法访问，`adb pull` 拿不到。本 App 借助 **Shizuku** 拿到 shell 身份，从而：

1. 列出并读取卡面目录下的所有图片（这些文件**通常没有后缀名**）；
2. 以网格形式展示卡面缩略图；
3. 选中一张卡面 → 从相册选自己的图片 → 自动按原图尺寸/格式重新编码 → **沿用原文件名**覆盖写入；
4. 写入前把原图备份到 App 外部私有目录；
5. 在「备份管理」里一键复原原卡面。

### OPPO 钱包卡面目录

```
/storage/emulated/0/Android/data/com.finshell.wallet/files/Finshell/Wallet/DownloadPics/
```

说明：

- 该路径**只写在本 README 里作为参考**，App 代码中没有任何硬编码；App 内是通过「选择应用 + 逐层浏览目录」来定位的，选完应用会优先打开 `/sdcard/Android/data/com.finshell.wallet`，再点进 `files/Finshell/Wallet/DownloadPics` 即可。
- 该目录属于 `Android/data` 外部私有目录，**shell(2000) 身份即可读写，不需要 root**。
- 不同系统版本 / 钱包版本目录结构可能有差异，若上面路径不存在，用 App 内的目录浏览按 `files → Finshell → Wallet → DownloadPics` 的顺序找。

---

## 二、运行前提（很重要）

| 条件 | 说明 |
| --- | --- |
| 安装并启动 Shizuku | 本机需有 Shizuku 服务在运行（本项目测试使用的是 `com.yuanqi.shizuku` 分发版；App 不依赖具体包名，只要它实现了官方 Shizuku Binder 协议即可） |
| 无需 root | 卡面在 `/sdcard/Android/data/com.finshell.wallet/` 下，**shell(2000) 身份即可读写** |
| 或者设备本身有可用的 `su` | App 会自动回退到 `su` 通道 |
| 已安装 OPPO 钱包 | 包名 `com.finshell.wallet`，且至少打开过一次让卡面素材落地 |

首页的「执行身份」一行会显示实际通道：

- `root（uid 0）· Shizuku UserService`
- `shell（uid 2000）· Shizuku UserService` ← 读写 `/sdcard/Android/data/...` 完全够用
- `root（uid 0）· su` ← 走 su 兜底
- `未连接` ← 未授权 / 服务未运行

---

## 三、使用步骤

1. 打开 App，授予 Shizuku 权限（首次会弹授权框）。
2. 首页点「选择目标应用」，在应用列表里找到 **OPPO 钱包（`com.finshell.wallet`）**。
3. 进入「目录浏览」，依次点进 `files` → `Finshell` → `Wallet` → `DownloadPics`（选完应用会自动落到 `Android/data/com.finshell.wallet`，省去从 `/sdcard` 开始翻）。
4. 点「查看卡面」，进入网格页：每张卡面显示缩略图 + 文件名 + 大小。
   - 卡面文件**没有后缀名**，App 不看扩展名，而是读文件头按真实格式识别（PNG/JPEG/WEBP/GIF/BMP/HEIF 等），所以无后缀也能正常显示；
5. 点任意一张卡面 → 「替换这张卡面」→ 从相册选图。
   - App 会按**原图的宽高**做等比缩放 + 居中裁剪，按**原图的格式**（PNG/JPEG/WEBP）重新编码；
   - 文件名**严格保持原文件名**，写入前自动备份原图；
   - 写完后读回校验字节，不一致会自动回滚。
6. 想还原：首页「备份管理」→ 选择该卡面 → 「复原」。

> 替换/复原后，通常需要**强制停止并重启 OPPO 钱包**（备份页提供「强停目标应用」按钮），否则它可能仍显示内存里缓存的旧卡面。

7. 注：该文件夹是 OPPO 钱包 APP 的图片资源文件夹，里面包含了诸多图片文件，本 APP 没有对无关图片进行筛选，所以需要用户自行甄别被替换图片，如果选择错误，建议及时通过恢复功能撤回修改。

---

## 四、实现要点

### 4.1 特权通道：Shizuku UserService + AIDL

`Shizuku.newProcess` 在 Shizuku API v13 中是 **private**，不可用。正确做法是 UserService：

- `app/src/main/aidl/com/cardface/studio/IRemoteFs.aidl`
  - 声明 `uidValue / listDir / statFile / sizeOf / readChunk / writeChunk / setStat / exists / isDirectory / deleteFile / exec`
  - 末尾必须有 `void destroy() = 16777114;`（Shizuku 专有约定）
- `RemoteFsService.kt`：继承 `IRemoteFs.Stub()`，由 Shizuku 在**自己的进程**里实例化（shell 或 root 身份），无需在 Manifest 注册
- `Privilege.kt`：`Shizuku.bindUserService(args, connection)` 建立连接，`args` 由
  `Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, RemoteFsService::class.java.name)).daemon(false).tag(...).processNameSuffix("fs").debuggable(...).version(...)` 构造
- 连接失败或设备有 `su` 时，自动回退到 `su -c` 通道（`PrivilegeMode{ NONE, USER_SERVICE, SU }`）

### 4.2 无后缀名文件识别

卡面文件的体积和格式千差万别，且多数**没有扩展名**：

- 列举目录时对每个文件只读 **64 字节文件头**（`readHead`），用 magic bytes 判断是否为图片（PNG/JPEG/WEBP/GIF/BMP + HEIF/AVIF 的 `ftyp` 盒）；
- 一个都没识别出来时（罕见格式）退回全部普通文件，交给解码阶段再判断；
- 替换时同样按文件头决定编码格式，而不是按扩展名。

### 4.3 大文件分块

Binder 单次事务上限约 1MB，故读写按 **256KB 分块**（`readChunk` / `writeChunk`），单文件上限 28MB。

### 4.4 保留属主与 SELinux 标签

写回时 Service 端用 `RandomAccessFile(path, "rw")` + 首块 `setLength(0)`，**保留原 inode**，从而保留属主/属组/权限/SELinux 标签；写后再用 `Os.chmod` / `Os.chown` + `restorecon` 兜底。

### 4.5 备份与复原

备份目录：`/sdcard/Android/data/com.cardface.studio/files/backups/<目标包名>/<备份ID>/`

- `原文件名`：原始字节
- `meta.json`：`originalPath / size / sha256 / mode / uid / gid / createdAt`

复原即把备份字节写回 `originalPath`，并同样恢复属性。

### 4.6 目录结构

```
app/src/main/
├── aidl/com/cardface/studio/IRemoteFs.aidl
├── java/com/cardface/studio/
│   ├── App.kt                 # ShizukuProvider 初始化、多进程支持
│   ├── Prefs.kt               # 目标包名/目录记忆
│   ├── ShizukuBridge.kt       # 授权状态与申请
│   ├── Privilege.kt           # 通道管理 + 读写/属性/exec（核心）
│   ├── RemoteFsService.kt     # 运行在 Shizuku 进程内的 UserService
│   ├── FileOps.kt             # 上层门面（FileEntry/Stat/ShellException/PathUtil）
│   ├── ImageCodec.kt          # 按原尺寸 cover + 原格式编码
│   ├── BackupStore.kt         # 备份写入/读取/列举
│   ├── CardEngine.kt          # 列表/备份/替换/复原 编排
│   ├── MainActivity.kt        # 首页：授权、选应用、入口
│   ├── AppListActivity.kt     # 应用列表
│   ├── FolderBrowserActivity.kt # 目录浏览
│   ├── GalleryActivity.kt     # 卡面网格 + 替换
│   └── BackupActivity.kt      # 备份管理与复原
└── res/...                    # 布局与主题
```

---

## 五、构建

```bash
export JAVA_HOME=<jdk17>
export ANDROID_SDK_ROOT=<android-sdk>   # 需 platform 34 + build-tools 34.0.0
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

依赖仓库已在 `settings.gradle.kts` 中配置了阿里云镜像（`maven.aliyun.com`），国内网络可直接构建；如镜像不可达，把 `settings.gradle.kts` 里 aliyun 两行删掉即可回落到 Google / Maven Central。

---

## 六、已知限制

1. **仅支持 OPPO 钱包（`com.finshell.wallet`）**：其它钱包 / App 的卡面目录、文件命名与加载方式不同，本项目不做适配。App 本身保留了选应用 + 浏览目录的能力，但那是为了定位 OPPO 钱包的目录，不代表通用。
2. **只需要 shell 身份**：卡面在 `/storage/emulated/0/Android/data/com.finshell.wallet/...` 下，Shizuku 用 ADB 或 root 任一方式启动都行。
3. **第三方 Shizuku 分发版兼容性**：`com.yuanqi.shizuku` 若未完整实现官方 UserService 协议，App 会自动退到 su 通道；两者都不可用则首页显示「未连接」。
4. **GIF/BMP/HEIF 卡面**：会以 PNG 内容写回但保持原文件名，若钱包按格式解码可能显示异常（可用备份复原）。
5. **格式识别**：仅依据文件头判断 PNG/JPEG/WEBP 等，未识别的按 PNG 处理。
6. 替换/复原后需强制停止 OPPO 钱包才能看到新卡面。
7. 钱包升级后卡面目录或缓存策略可能变化，届时需要重新在 App 内定位目录。

---

## 七、免责说明

本项目仅供在**你自己拥有或已获授权的设备**上做个性化，**只针对 OPPO 钱包**的卡面文件。

- 修改应用私有资源可能违反 OPPO 钱包的用户协议，并可能导致卡面显示异常、App 校验失败等情况，请自行判断并承担风险；
- 替换前 App 会自动备份原图，但**请自行额外保留一份原始素材**，并在出现异常时先复原；
- 请勿将本项目用于任何商业用途或分发修改后的钱包资源。

---

## 八、许可证

[MIT License](LICENSE) © 2026 vastmarsh

仅供个人学习与自用设备个性化；由此产生的账号、设备或钱包相关风险由使用者自行承担。
