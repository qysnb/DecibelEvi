# 构建与本地签名

## 环境

- JDK 17；Gradle wrapper 8.11.1；Android Gradle Plugin 8.9.2。
- Kotlin 2.1.20、Jetpack Compose、Room/KSP。
- Android SDK Platform 35、Build Tools 36.1.0；最低系统API29。
- Python 3.10及以上用于tools脚本和测试，无第三方Python依赖。

在Android Studio打开项目并选择JDK17。通过SDK Manager安装上述SDK组件。让Studio生成本机local.properties，或设置ANDROID_HOME指向本机SDK。不要提交local.properties。

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
python -m unittest discover -s tools/tests -v
```

Linux/macOS使用`./gradlew`。首次构建需下载Gradle及Google Maven/Maven Central依赖；不包含SDK、缓存或密钥，不要求使用开发者的磁盘目录。core使用Java17 toolchain；若Gradle本身用其他JDK，另安装JDK17并在本机用户Gradle配置中指定，勿写入公共工程路径。

Debug输出为app/build/outputs/apk/debug/app-debug.apk。未配置正式签名时Release输出为app/build/outputs/apk/release/app-release-unsigned.apk，不能当作可安装正式包。

## 正式签名

Android Studio：Build → Generate Signed Bundle / APK → APK → 选择本人保管的密钥 → release。密钥及密码不入仓库。官方构建长期使用同一签名；自行签名的构建可能无法覆盖安装官方版。

官方签名证书SHA-256：
`56cb74ec48203d196163684ed3122fc76d58422017060c337d224ee42f08eaf8`

公开证书不包含私钥；不要上传或分享密钥库。使用SDK Build Tools中的apksigner验证成片：

```text
apksigner verify --verbose --print-certs app-release.apk
zipalign -c -P 16 4 app-release.apk
```

Release附件SHA256SUMS.txt记录实际发布文件摘要；源码tag与发布版本关联。不同工具链、路径或签名时刻可能影响字节，当前不承诺字节级可复现。

## 设备测试

开启USB调试并连接设备，执行`adb devices`，安装Debug构建后授予测试需要的权限，再运行：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

模拟器与JVM结果不代表真机声学精度或全部设备兼容；实际覆盖见VALIDATION.md。tools/adb_probe.py支持ANDROID_HOME或ANDROID_SDK_ROOT查找ADB，ANDROID_SERIAL选择设备；本地UI日志写入.tools，不提交仓库。

## 依赖许可审计

在工程根目录运行Gradle清单任务：

```powershell
.\gradlew.bat -I tools/license-inventory.init.gradle :app:commercialLicenseInventory
```

再用tools/audit_licenses.py，指定生成的.tools/commercial/runtime-artifacts.json、当前GRADLE_USER_HOME下的caches/modules-2/files-2.1及--root .。脚本解析真实Release运行时构件与父POM许可，生成根目录及assets中的第三方声明；内部审计输出位于被忽略的docs/commercial。依赖变更后应重新执行并审阅结果。
