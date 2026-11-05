# Spatial Wall Android導入アプリ

PCのSpatial Wallから、同じLAN内でAndroid本体アプリを取得するオープンソースの導入アプリです。外部サーバーからアプリを取得しません。

1. PCでSpatial Wallを開き、スマホリモコンを有効にします。
2. PCの新しいQRまたは6桁コードでAndroid端末を登録します。招待は60秒で期限切れになるため、導入アプリのインストール前に読み取ったQRは使えないことがあります。登録時には「リモコン」の権限を有効にしてください。
3. 導入アプリが認証済みTLSでPCから本体を取得し、SHA-256・パッケージ名・版番号・署名証明書を検証してAndroidのインストール確認を開きます。
4. Androidが求めたら、Spatial Wallからのインストールを許可してください。更新後にSpatial Wallを開くと、PCへの登録が引き継がれます。

公式の導入アプリと本体は、同じパッケージ名・署名証明書を使います。導入アプリの版番号は低いため、すでにインストール済みの本体を上書きできません。Android 11以降、64bit ARM端末が必要です。

導入アプリのソースはMITです。BoringSSLは `src/core/remote_pairing/boringssl.json` に固定した未改変版を使い、BoringSSLとAndroid NDKランタイムのライセンス・通知をAPKへ同梱しています。画面の「オープンソースライセンス」から参照できます。これらはクローズドソースの有償Spatial Wallへの利用を許諾する条件です。本体の利用許諾は別に定めています。

公開ソースにはビルド用のGradle wrapperも含みます。Apache-2.0の原文を `gradle-wrapper-LICENSE.txt` とwrapper JARに保持しています。GradleはAPKへ同梱しません。

## ソースからのビルド

公開ソースは本体リポジトリからの相対パスを保っています。そのソースディレクトリのルートで作業してください。

1. JDK 17以降・Python 3・Git・Android SDK platform 36.1・NDK 28.2.13676358・SDK CMake 3.22.1を用意し、Android SDKのライセンスに同意します。
2. `ANDROID_SDK_ROOT` を設定します。`android/local.properties` に `sdk.dir=<SDKのパス>` をJava propertiesのエスケープで記載し、このSDK設定を `android/installer/local.properties` にも配置します。
3. `python scripts/setup-remote-pairing.py` で固定版BoringSSLを取得・検証します。
4. `android/gradlew -p android/installer assembleDebug testDebugUnitTest` を実行します。Windowsでは `gradlew.bat` を使います。

自前ビルドは自分のdebug鍵で署名されるため、公式版を上書き更新できません。製品の秘密署名鍵は公開ソースに含みません。開発者のrelease鍵を使う場合は、ignore対象の `android/local.properties` に `nyan.keystore`・`nyan.keystore.password`・`nyan.keystore.alias` を指定できます。keystoreは絶対パスを使用し、鍵や設定はコミットしないでください。

本体の開発リポジトリでは、Windowsは `scripts/build-android.ps1 -Installer -Release -Publish`、Linux/macOSは `scripts/build-android.sh --installer --publish` を使います。Publishには製品署名証明書が必要で、`dist/android-installer/spatialwall-installer.apk` にのみ配置します。

ビルドと公開ソースの書き出しは `android/installer/shared-java.txt` を共用します。登録・保護された資格保存・APK検証は、本体の正本コードをそのまま使用します。
回帰試験も `shared-tests.txt` で共用します。JUnit 4.13.2は試験専用の依存で、APKには含みません。
