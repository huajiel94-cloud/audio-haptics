# 音频联动 · Quest 原生版

一个 App 里做完四件事：**选视频 → 预分析音轨 → 播放 → 按强度写 BLE**。
算法与 APK 一致（`RMS→dBFS→[−50,−8]→0.6/0.2 平滑`），已在浏览器里通过 28 项断言。

> ⚠️ 我这台机器 shell 起不来，**无法编译验证**。代码按最保守写法给，编译报错把行号贴我，我直接改。

---

## 1. 这个目录已经是一个完整工程

```
native/
  settings.gradle.kts          ┐
  build.gradle.kts             ├ Gradle 配置（AGP 8.2.2 / Kotlin 1.9.22）
  gradle.properties            │
  app/build.gradle.kts         ┘ 依赖已配好（media3 已加）
  .github/workflows/build.yml  ← 云端编译用，本地产 APK 不需要
  app/src/main/AndroidManifest.xml
  app/src/main/java/com/dsh/audiohaptics/
      MainActivity.kt          界面 + 播放 + BLE
      AudioAnalyzer.kt         解音轨 → PCM → 包络
      Envelope.kt              算法常数（与 APK 对齐）
      Protocol.kt              报文模板
  app/src/main/res/layout/activity_main.xml
  app/src/main/res/values/strings.xml
```

不用再从模板新建工程，也不用改依赖。

## 3. 出 APK：两条路，选一条

### 路 A · 本地 Android Studio

`File → Open` 直接打开 **本目录**（`native/`），等 Gradle Sync 完，`Build → Build APK(s)`。
产物：`app/build/outputs/apk/debug/app-debug.apk`

> 工程文件已经齐了（`settings.gradle.kts` / `build.gradle.kts` / `app/build.gradle.kts`），
> 不需要再从模板新建。如果 Studio 提示"缺 Gradle wrapper"，让它自动生成即可（或忽略，用它自带的 Gradle）。

### 路 B · 云端编译（不装 Android Studio）

1. 在 GitHub 建一个空仓库
2. 把 **本目录的所有内容** 传上去（网页拖拽即可，注意保持目录结构，`.github` 也要传）
3. 仓库 → Actions → `build-apk` 会自动跑；也可以点 `Run workflow`
4. 跑完在这次运行的页面底部下载 **artifact**：`audio-haptics-debug.zip` → 里面是 `app-debug.apk`

需要免费的 GitHub 账号，不需要信用卡，不需要装任何本地工具。

## 4. 装到头显

`adb install -r app-debug.apk`，或 QuestTool 拖进去。
头显 → 应用库 → 来源不明 → **音频联动**。

---

## 5. 用起来

1. **扫描设备** → 点你的玩具（先让手机上的醉清风 App 断开）
2. 连上后列出所有特征值，**灰色是只读**，点一个带 `W` / `WNR` 的
3. 把「手动测试强度」拉到 60 → **发一帧** → 看玩具动不动
   - 动 → 特征值对了，协议也可能对了
   - 不动 → 换特征值 / 勾「无响应写入」/ 把上限改 255 / 换模板
4. **选视频** → 等它分析（进度百分比）→ **播放**
5. 播放时会自动按 20 Hz 把当前强度写进 BLE

> 分析是一次性的：同一个视频重新选会重算，目前没做磁盘缓存（后面可以加）。

## 6. 协议还没定，怎么调

模板默认 `AA 03 {level} 55` 是**占位**。真正格式要靠抓包确定：

- `docs/PROTOCOL.md` 里三种抓法（Frida / HCI snoop / 试错）
- 抓到后把两帧填进网页版的「报文标定」器，它会算出模板，再抄到这里的输入框
- 如果设备需要先握手，目前的原生版**还没有初始化帧**功能（网页版有），告诉我我给你补

## 7. 判读

| 现象 | 结论 / 下一步 |
|---|---|
| 扫描列表空 | 权限没给 / 玩具被手机占用 / 蓝牙没开 |
| `扫描失败 errorCode=N` | 把 N 发我 |
| 连不上 status 133 / 8 | 设备被别的中心连着，或需要配对 |
| 连上但 0 个可写特征值 | 选错设备，或该设备要求先握手 |
| 能发字节但玩具不动 | 报文格式不对 → 抓包 |
| 视频分析出来 0 点 | 该文件音轨是特殊编码 → 换个文件试 |

## 8. 还没做的

- 360°/180° 视频（现在是平面 SurfaceView；VR 需要球面/立方体渲染或转 Unity）
- 协议配置持久化（现在重启要重填）
- 握手帧 / 校验位
- 包络磁盘缓存（长视频每次重选都要重算，3 分钟的视频约 2~5 秒）
