# 校园随练 · 2.0.4 云端 APK 自动构建工程

**这是 Android 源码，不是 APK。** 这是根据已提供的 2.0.2 工程制作的 2.0.4 修订版。
它已包含 GitHub Actions 工作流。云端构建**尚未实际运行**，真实 APK 生成后才算构建完成。

## 第一次使用（无需在电脑安装 Android Studio / SDK / Gradle）

1. 在 GitHub 创建一个 **Private（私有）** 空仓库。不要初始化 README。
2. 下载并解压本 ZIP，然后在仓库中选 **Add file → Upload files**，
   把**解压后文件夹里的所有内容**拖入（包括隐藏的 `.github` 目录），提交。
   不要直接把 ZIP 上传到仓库里：GitHub 不会自动解压。
3. 打开仓库 **Actions** → 选择 **CampusPocket 2.0.4 · Build APK**。
   首次上传会自动触发构建；如未运行，点击 **Run workflow**。
4. 构建成功后，在对应运行页的 **Artifacts** 下载
   `CampusPocket-2.0.4-debug-APK`，解压后得到 `app-debug.apk`。
5. 如果构建失败，下载 `Build-logs-for-debugging` 交给开发者排查。
   不必自己改 Gradle 或 SDK。

整个仓库包精简到少于 100 个文件，符合 GitHub 网页单次上传上限。
仓库应设为 Private，避免公开源码和内部测试逻辑；**没有打包 API Key、个人录音或账号数据**。

## 版本与安装

- `versionName`: 2.0.4（Debug 后缀 `-debug`）
- `versionCode`: 27
- `compileSdk`: 34 / `minSdk`: 26 / `targetSdk`: 34
- Gradle: 8.2.1 / Android Gradle Plugin: 8.2.2 / Java: 17
- Debug 安装包使用独立应用 ID `dev.campuspocket.app.observer.releasefix.debug`。
- **可以与 2.0.2 并存，不能覆盖旧版升级，也不会继承旧版 API 设置。**
- GitHub Actions 的 Debug 包使用自动生成的 Debug 签名；多次构建之间签名不保证一致。
- 不包含原 2.0.2 发布签名私钥。

## 本地可检查项目

ZIP 顶层结构：
- `.github/workflows/build-apk.yml`：自动安装 SDK、跑回归、编译、上传 APK
- `current/campus-pocket/`：完整 Android App 源码
- `verification/`：JVM 回归测试与必要的 Android 测试桩

本地检查（需要 JDK17）：
```
bash verification/run-local-regressions.sh
bash verification/run-source-checks.sh
```
在具备 SDK/Gradle 的环境编译：
```
cd current/campus-pocket
gradle :app:assembleDebug
```

## 尚待真机验证

U校园真实录音开始/结束判断、系统对外放声音的实际收录、不同手机无障碍手势
与弹窗、跨题跳转。离线回归通过不能替代真机验收。正式评分流程须符合相应授权与课程规则。
