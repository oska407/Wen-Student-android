# 雯雯做题 · 学生端安卓壳

一个很薄的安卓 App：**把电脑上那套「学生端网页」装进一个手机壳里**。
出题、判分、录音、统计全由电脑上那套网页负责，这里一行业务代码都没有。

- 现版本：**v1.2（含顶栏小键、父母 PIN、宽度铺满）**
- 支持 **扫码连接** 和 **手打地址** 两种方式
- 需要手机 **Android 8.0（华为荣耀 8 以上）**
- 不用装 Java / Android Studio：走云端打包（路线 B）

---

## 一、这个目录里有什么

```
Wen-Student-android/
├─ .github/workflows/      云端打包的两个自动化流程
│  ├─ build-apk.yml        编译 APK + 顺带出二维码
│  └─ make-qr.yml          只出二维码（换 IP 用，几十秒）
├─ app/
│  └─ src/main/
│     ├─ AndroidManifest.xml    权限：网络、麦克风、相机
│     ├─ java/com/wenwen/student/
│     │  ├─ MainActivity.java    首屏（扫码/手输）+ WebView 容器
│     │  ├─ ScanActivity.java    扫码页
│     │  └─ ViewFinderFrame.java 取景框
│     └─ res/
├─ build.gradle / settings.gradle / gradle.properties
└─ README.md（本文件）
```

> ⚠️ 上传到 GitHub 时，**根目录必须是这一层的东西**（`build.gradle`、`app/`、`settings.gradle` 挨着）。
> 别多套一层 `Wen-Student-android/`。

---

## 二、选哪条路

| | 路线 B：GitHub 云端打包 ✅ 推荐 | 路线 A：本地装 Android Studio | 手机浏览器「添加到主屏幕」 |
|---|---|---|---|
| 电脑要装什么 | **什么都不用装**，只装个 GitHub | 1GB 安装 + 500MB~1GB 组件下载 | 不用装 |
| 第一次要多久 | 注册 + 上传 + 等编译 ≈ **30~40 分钟** | 装软件 + 同步 + 编译 ≈ 1 小时+ | 2 分钟 |
| 以后改代码 | 拖文件 → 自动重编 | 点 Build APK，几十秒 | — |
| 换 IP 要重编吗 | **不用**（重新出张二维码即可） | 不用 | 不用 |
| 能不能录音 | 能 | 能 | **不能**（非安全上下文，麦克风拿不到） |

**结论：走路线 B。**

> 手机浏览器那条路**走不通**：Chrome 不认电脑的自签证书，而且即使点了"继续"，
> 非安全上下文下麦克风直接被浏览器掐掉，朗读题和录音题全废。必须是个真正的 APK。

---

## 三、路线 B：云端打包（照着做就行，一个字代码都不用写）

### 第 0 步 · 先确认两件事

1. **电脑上出题服务是开着的** —— 手机访问 `https://192.168.3.15:3000/student` 能打开
2. **手机和电脑连的是同一个 WiFi** —— 这点九成的问题出在这

顺手确认一下电脑的 IP（万一不是 192.168.3.15）：
> 电脑按 `Win + R` → 输入 `cmd` 回车 → 输入 `ipconfig` 回车 → 找「IPv4 地址」

### 第 1 步 · 注册 GitHub

1. 打开 **github.com** → 点右上角 **Sign up**
2. 填邮箱 → 设密码 → 收验证码 → 注册
3. 登录后，**右上角头像 → Settings**，把邮箱验证一下（Actions 要收通知）

> 全程用邮箱就行，不需要记密码以外的东西。

### 第 2 步 · 建一个「私有仓库」

1. 登录后的任意页面，右上角 **＋ → New repository**
2. 仓库名填：`wenwen-student`（随便，别用中文）
3. **Description**：可以空着
4. **重要：选 Private（私有）** ⚠️ —— 千万别选 Public，不然家里的 IP 和系统细节就公开了
5. 下面三个选项都**别勾**（别初始化 README、别加 .gitignore）
6. 点 **Create repository**

### 第 3 步 · 把文件放进去

三种办法，选你顺手的：

**办法 1 · GitHub 网页拖文件夹（最简单）**
1. 进了新仓库，点 **Add file → Upload files**
2. 把电脑上的 **`Wen-Student-android` 整个文件夹** 拖进那片虚线框
3. 等它上传完 → 点 **Commit changes**
4. 确认一下仓库里是这个样子（**不能有 Wen-Student-android 这一层**）：
   ```
   .github/
   app/
   build.gradle
   settings.gradle
   gradle.properties
   ```

**办法 2 · GitHub Desktop（拖文件夹更顺手）**
1. 下载装 **desktop.github.com** 那个 GitHub Desktop
2. 打开 → `File → Clone repository` → 登录你的 GitHub → 选这个仓库
3. 文件夹出来后，把 `Wen-Student-android` 里的东西全拖进去
4. 回到 GitHub Desktop → 左下角点 **Commit to main** → 点 **Push origin**

**办法 3 · 命令行**（会用才用）

```bash
git init
git add .
git commit -m "first commit"
git branch -M main
git remote add origin https://github.com/<你的用户名>/wenwen-student.git
git push -u origin main
```

### 第 4 步 · 打开 Actions 开关

1. 仓库顶部点 **Settings** 页签
2. 左边点 **Actions → General**（在 "Code and automation" 下面）
3. 最上面 **Actions permissions** 选 **Allow all actions and reusable workflows**
4. 页面拉到最下面，把 **Workflow permissions** 勾成 **Read and write permissions**
5. 点 **Save**

### 第 5 步 · 手动跑一次编译

1. 仓库顶部点 **Actions** 页签
2. 左边列表里会出现 **打包安卓 APK** → 点它
3. 右上角 **Run workflow** 下拉 → 勾上 **main** → 点绿色 **Run workflow**
4. 如果想换地址：在 `server` 那栏填你的 IP，不填就是默认的 `192.168.3.15:3000`

> 💡 **第一次要先 Push 过代码，Actions 页签才会出现。** 第 3 步做完就有了。

### 第 6 步 · 等 + 下载

1. 点进去那次运行（顶部会有一条黄/蓝的进度）
2. **第一次大概 15~25 分钟**（要下 Java、Android SDK、Gradle 和一堆依赖包），中途别关页面
3. 变成绿色 ✅ 后，页面底部 **Artifacts** 会出来两个下载按钮：

| Artifact | 里面是 | 干什么用 |
|---|---|---|
| `wenwen-student-apk` | `app-debug.apk` | **装手机的 App**（可能有个 zip 包，解压一下） |
| `连接二维码` | `connect-qr-192-168-3-15-3000.png` | 手机扫码连电脑的码 |

4. 点名字 → 下载 → 得到一个 zip → 右键**全部解压**

> ⚠️ 如果黄了 ❌：点那个红叉看日志，最常见问题见本文第六节。

### 第 7 步 · 装到手机上

1. 把 `app-debug.apk` 弄到手机上：
   - 最简单：**微信「文件传输助手」**发给自己 → 手机微信里点开 → 选"用其他应用打开"
   - 或者：手机数据线连电脑 → 把 apk 拷到手机Downloads文件夹 → 用「文件管理」点它
2. 点开后 Android 会问「不允许安装未知来源的应用」→ 点**允许一次**（关掉这次那个权限开关就行）
3. 装完桌面出现 **雯雯做题** 图标 → 点开

---

## 四、第一次怎么用（扫码 or 手输）

App 打开是**整屏**学生端页面。最顶部一条细窄的灰色小栏（在页面上方，**不压做题按钮**），
右边有两枚小图标键：**⚙ 设置**、**⟳ 重连**。

### 方式一：扫码（推荐，省事不打字）

1. 电脑上用**照片查看器**打开那个 `connect-qr-*.png`
2. 按 `F11` 全屏（笔记本按 `Fn + F11`），把二维码放正
3. 手机第一次打开 App 会弹出地址面板 → 点 **扫 一 扫**
4. 手机对着屏幕扫一下 → 自动连上，**不用打字**

### 方式二：手打地址

1. 在第一次弹出的地址面板里直接填 `192.168.3.15:3000`（**不用带 https://**）
2. 点 **手动输入** 连上

> 地址会存在手机里，**下次打开直接进做题页**，不用再填。

### 连上之后

- 顶部小灰条显示「在线」= 电脑连着；「离线」= 电脑关机或不在同一个 WiFi
- 链接上之后**手机可以断 WiFi 数据也行**，只要电脑和手机在同一个网络
- 第一次打开会加载一会儿（要连电脑取题），**别急着退出**

### 以后想换服务器 / 重连 / 改设置

- **⟳ 重连**：点它重新打开扫码（想换到另一台电脑、或扫码没扫到时用）。
  这个键和 ⚙ 都受**父母 PIN** 保护——孩子没法偷偷断连或换服务器。
- **⚙ 设置**：点它进设置面板，里面有「改连接地址」「设/改父母 PIN」「声音开关」等。
  第一次没设 PIN 时直接进；设过 PIN 后要先输入 PIN 才进得去。
- **忘记父母 PIN**：只能在手机「设置 → 应用 → 雯雯做题 → 清除数据」清空 App，
  然后重扫二维码连一次（PIN 和地址一起清空，不丢电脑上的任何数据）。

---

## 五、以后要改东西怎么办

| 想干什么 | 怎么做 | 要等多久 |
|---|---|---|
| 改 App（比如换个入口） | 改文件 → Push → Actions 自动重编（push 分支也会触发） | 15~25 分钟 |
| **只换电脑 IP** | 仓库 → Actions → **只生成连接二维码** → Run workflow → 填新 IP | **1 分钟** |
| 装手机 | 第 3 步那样拖文件 | — |

> **换 IP 千万别重新编译 APK**，只跑「只生成连接二维码」就行，APK 不用换。

---

## 六、出问题看这里

| 现象 | 怎么处理 |
|---|---|
| Actions 跑黄了/红叉 | 点红叉 → 展开 `build` job → 看红色报错行。最常见的三个见下面三行 |
| `Permission to run "assembleDebug" denied` | 第 4 步第 3 小步的开关没开 |
| `No space left on device` / 编译超时 | 重跑一次（点 Run workflow 的重新跑那个按钮），一般第二次就过 |
| GitHub 一直卡在下载 / 上不去 | 国内网络不稳，多试几次；**实在不行改路线 A（本地打包），见下一节** |
| 手机打不开 `192.168.3.15:3000` | 九成是手机电脑没同一 WiFi；进来看 `ipconfig` 的 IP 是不是同一个段 |
| 手机弹「连接不是私密连接」 | 正常情况壳会自动放行；如果还弹，说明地址末尾漏了 `/student` |
| 扫二维码没反应 | 相机权限被拒过（设置 → 应用 → 雯雯做题 → 权限 → 开相机）；或者把二维码放大点、离近点 |
| 能打开但**录音没声音/没反应** | 一定是走的 http://。去 App 里点 **⚙ 设置 → 改连接地址**，确认填的地址**没有 http:// 前缀**（壳默认补 https://） |
| 装 apk 时提示「解析失败/包有问题」 | 下载的 zip 没解压；重新从 Artifacts 下 `wenwen-student-apk` 解压后再装 |

---

## 七、备用：路线 A 本地打包（如果 GitHub 实在上不去）

1. 装 **Android Studio**：`developer.android.com/studio`（Windows 的 `.exe` 约 1GB）
   安装向导里务必勾上 **Android SDK / Android SDK Platform 34 / Android SDK Build-Tools / Android Emulator**
2. 打开 Studio → **File → New → New Project → Empty Activity → 语言选 Java → Minimum SDK 选 Android 8.0 (API 26)**
3. **把新工程里自动生成的文件都删掉**，然后用本目录的同名文件逐个覆盖：
   ```
   Wen-Student-android/build.gradle              → 工程根目录
   Wen-Student-android/settings.gradle           → 工程根目录
   Wen-Student-android/gradle.properties         → 工程根目录
   Wen-Student-android/app/build.gradle          → app/
   Wen-Student-android/app/src/main/AndroidManifest.xml               → app/src/main/
   Wen-Student-android/app/src/main/java/com/wenwen/student/*.java    → app/src/main/java/com/wenwen/student/
   Wen-Student-android/app/src/main/res/**                            → app/src/main/res/
   Wen-Student-android/.github/                  → 可不加
   ```
4. 弹窗点 **Sync Now**（右上角那个带小棒的图标）
5. **Build → Build Bundle(s)/APK → Build APK**
6. 产物在右下角提示里点 **locate** → `app/build/outputs/apk/debug/app-debug.apk`

> 第一次构建 15~30 分钟（要下 Gradle），之后几秒。全程联网，中途别关。

---

## 八、这个 App 现在是什么能力（边界，先看清楚）

**已经有的**
- 全屏学生端，就是电脑上那个页面，公式/判分/统计都一样
- 页面**铺满手机屏幕宽度**，不会缩小成小桌面版
- 自签证书自动放行（**不需要在系统里装任何证书**）
- 麦克风权限自动给，朗读题、录音题可用
- 提示音不用先点屏幕就播（首次触摸自动解锁音频）
- 两种连接方式：扫码 / 手打，地址存本地
- 顶栏 ⚙ 设置 / ⟳ 重连 两枚小键（**不压页面按钮**）
- **父母 PIN**：锁住「设置 + 改地址 + 重连」，孩子不能偷偷断连或换服务器
- 顶栏看在线/离线

**还没做的（离线相关的，都是后端下发才生效）**
- 离线做题 + 断网自动补传（需要服务端 `sw.js` + 握手接口，属 P1）
- 现在编出来的版本：**必须连着电脑才能做题**

> 好消息是：等到要做离线时，**App 不用重新打包** —— 缓存脚本由电脑上的网页自己下发。
