# Privacy Notice — nyan Real / Spatial Wall (standalone app)

**Last updated: 2026-11-05**

This is the privacy notice for the **standalone nyan Real / Spatial Wall
application** (`spatial-wall.exe` on Windows, `spatial-wall` on Linux and
macOS), with an additional section for the Android glasses bridge RDP client.
Unless stated otherwise, "the app" below means the desktop application. The
browser extension has its own
[privacy notice](https://support.nyanreal.jp/spatial-wall/legal/PRIVACY-chrome-extension.md).

---

## English

### Summary

**The Spatial Wall desktop app contacts no server on the internet.** It makes no update check and
reports no usage or crash data. Everything it
processes — screen images, audio, head tracking — stays on your own computer or
inside your own network.

### No external communication

The app contains no analytics, telemetry, crash reporting, advertising or
third-party tracking SDK. There is no automatic update check and no account,
sign-in or activation server.

Support is deliberately manual: "About" > "Copy support info" puts a text block
on your clipboard that you choose to paste into a GitHub Issue yourself. Nothing
is sent for you.

### Where the network is used

Networking is limited to the following local paths. Each stays on your own
machine, your local network, or the direct device link to the glasses:

| Feature | Destination | What travels |
|---|---|---|
| Phone remote | a browser on a device on your local network | the remote-control page and the app's control state |
| Android glasses bridge | the companion app on your Android device, on your local network | video frames, audio, head-tracking and control messages |
| Browser extension (tab audio) | loopback on the same PC (`ws://127.0.0.1`) | tab audio and window position |
| Local control (Stream Deck or scripts) | loopback on the same PC (`ws://127.0.0.1`) | control commands and app state |
| XREAL One-family device link | the glasses over TCP/IP through USB Ethernet | head-tracking and device-control messages |

To let a phone find the PC by name, the app answers multicast DNS (mDNS) queries
on the local network. That response contains the PC's host name and the port
number, and it does not leave your network.

The phone remote and the bridge listen on your local network, so anyone already
on that network can reach them while they are enabled. Use them on a network you
trust, and turn them off when not in use.

Phone control, companion APK downloads, and the Android bridge use authenticated
encryption on the LAN. Registration establishes the PC's identity using its QR
invitation or six-digit code; reconnecting checks the saved identity. Public
connection-setup data can be fetched over HTTP, but the code and saved access
credential are not sent in plaintext. Device access can be revoked individually
or together on the PC, which also closes the affected control, video and audio connections.

### Official browser entry and Android app downloads

The phone's browser opens `https://support.nyanreal.jp/spatial-wall/remote/` to
obtain the open-source entry and its fixed connection tools. The browser contacts
that website for the first load and service-worker update checks. The hosting
provider receives ordinary web-request information, including the connecting IP
address and browser headers. This is separate from the desktop app's LAN traffic.
No analytics or third-party scripts are included in the entry.

If you install the remote on your Android home screen, Chrome may send the
public app name, icons and fixed launch URL to Google's WebAPK service to create
or update the installed web app. These details contain no PC address, invitation
or device credential.

After all entry assets have been saved, the browser can reopen them offline.
The cache contains only the fixed entry assets, not LAN responses, screen images,
registration invitations, or access credentials. The actual remote-control UI is
obtained from the connected PC, verified, and run separately from the entry's
saved credentials. Controls and screen data are sent directly to that PC, not
through the support website.

The QR's local PC address and short-lived invitation are in the URL fragment,
which is not sent in the website request. The entry removes the fragment from the
address bar before loading its modules. Saved PC addresses, public identities,
permissions and device access credentials remain in that browser profile's local
storage. Other code trusted at the same website origin and anyone with access to
the profile may access that storage. Use your own profile and revoke unwanted
devices on the PC. Clearing the browser's saved PC list does not revoke the PC-side
registration.

When you choose to install or update the Android app, the browser downloads the
APK from your registered Spatial Wall PC over the authenticated, encrypted LAN
connection. It verifies the product-signed release information and the APK's size
and hash before offering to save the file. You open the downloaded file and confirm
installation in Android. The support website serves the browser entry. The APK
transfer and the browser's device credential stay between your browser and your PC.
The Android app registers separately with the PC on first use.

Spatial Wall's Android PC registrations are encrypted with an app-private Android
Keystore key and excluded from backups. They include the PC's public identity,
device access credential and permissions; the display name and PC address are
saved separately as local settings. Normal reconnection does not require biometric
authentication. Clearing or losing registrations requires pairing again. These
registrations are separate from the RDP credential store described below.

### What is captured, and where it goes

To draw the wall the app captures your screens and the audio of playing apps,
and reads head tracking from the glasses. This data is processed in memory and
sent to the glasses over USB/DisplayPort, or to your own Android device over the
local network when the bridge is in use. **It is not written to disk and not
transmitted anywhere else.** No camera or microphone is used.

To place each window in space, the app reads the window list of the operating
system, including window titles and process names. This stays in memory on your
computer.

### Data stored on your computer

| Location | What is stored |
|---|---|
| `%APPDATA%\nyan-real\` (Windows) — the OS user config directory on Linux and macOS | your settings |
| `%APPDATA%\nyan-real\logs\` | diagnostic logs, one file per launch |
| the app's protected remote-access store | PC identity key and registered device labels, permissions and credential verifiers |

Logs record what the app and the connected glasses did — device model, firmware
version, capture and rendering diagnostics — so a problem can be investigated.
They stay on your computer. They are only shared if you attach one to a support
request yourself.

"Advanced" > "Reset settings to defaults" returns the settings to their initial
state. It retains registered phones. Remove those registrations separately in
the phone remote settings.

### Connected glasses

Over USB the app reads what the glasses report — model, firmware version,
temperature, sensor data, and the factory calibration stored in the device. This
is used to render correctly and is shown in the app. It is not transmitted
anywhere.

### Android glasses bridge: RDP connections

The Android companion can connect directly to a remote PC on your local network.
**Only a connection to an RD Gateway that you specify and choose to connect to
may contact a server on the internet.** The desktop application's no-external-server
policy remains unchanged. RDP uses TLS and validates both the remote PC's and the
Gateway's certificates against system CAs and CAs installed on your Android device.

The selected PC and, when configured, Gateway receive the authentication data
needed for their respective logins. Keyboard and pointer input goes to the remote
PC, and its desktop images and playback audio are received by the companion. Head tracking is
processed locally to display that desktop. This RDP client does not redirect the
clipboard, files, drives, microphone or camera. There is no vendor relay,
telemetry or automatic connection to an internet service.

Multiple connection profiles, including account names and any passwords you elect
to save, are encrypted with AES-256-GCM in app-private, non-backup storage. The key
is held by Android Keystore; each unlock and save requires strong biometrics or
the device's screen-lock credential. The app does not receive biometric data.
Closing or leaving the connection manager locks its saved information. An active
RDP session continues until disconnected and temporarily uses credentials in memory;
locking the connection manager does not log out the remote session.

Saved profiles are not backed up or transferred to another device. Removing the
app, resetting saved connections, or losing the Keystore key requires registering
the profiles again. Connection credentials are not put into diagnostic logs or
the remote-control WebView. Delete individual profiles from RDP connections, or
use Reset saved connections to erase all profiles and their key.

### Changes to this notice

If this notice is updated, the "Last updated" date above will be revised.

### Contact

8796n <info@8796.jp> —
https://github.com/8796n/nyan-real-support/issues

---

## 日本語

### 要約

**Spatial Wall 本体はインターネット上の外部サーバーとは通信しません。** 更新の自動確認も、
利用状況やクラッシュ情報の送信も行いません。処理する
情報（画面の映像、音声、頭の動き）は、お使いのコンピューターの中か、
ご自身のネットワークの中で完結します。

### 外部通信を行わないこと

本アプリには、アナリティクス、テレメトリ、クラッシュレポート、広告、
第三者の追跡用SDKは含まれません。更新の自動確認もなく、アカウント、
サインイン、ライセンス認証サーバーもありません。

サポートも意図的に手動です。「バージョン情報」の「問い合わせ用情報をコピー」は
テキストをクリップボードに置くだけで、それを GitHub Issue に貼るかどうかは
お客様が決めます。自動で送信されるものはありません。

### ネットワークを使う箇所

ネットワーク通信は次のローカル経路に限られます。いずれもお使いのマシン、
ご自身のローカルネットワーク、またはメガネとの直接接続の中で閉じています。

| 機能 | 通信先 | 流れるもの |
|---|---|---|
| スマホリモコン | 同じネットワーク上の端末のブラウザ | リモコン画面と、アプリの操作状態 |
| Android メガネブリッジ | 同じネットワーク上の Android 端末のアプリ | 映像フレーム、音声、頭の動き、操作メッセージ |
| ブラウザ拡張（タブ音声） | 同じ PC 内のループバック（`ws://127.0.0.1`） | タブの音声とウィンドウ位置 |
| ローカル操作（Stream Deck / スクリプト） | 同じ PC 内のループバック（`ws://127.0.0.1`） | 操作コマンドとアプリの状態 |
| XREAL One 系との接続 | USB Ethernet 経由の TCP/IP で直接接続したメガネ | 頭の動きと機器制御のメッセージ |

スマートフォンから PC を名前で見つけられるようにするため、本アプリは
ローカルネットワーク上の mDNS 問い合わせに応答します。応答に含まれるのは
PC のホスト名とポート番号で、ネットワークの外には出ません。

スマホリモコンとブリッジは、有効にしている間、同じネットワーク上の端末から
到達できます。信頼できるネットワークでお使いいただき、使わないときは
オフにしてください。

スマホの操作、連携APKの取得、Androidブリッジには、LAN内で認証付きの暗号化を使用します。
QRの招待または6桁コードによる登録でPCを確認し、再接続では保存済みのPCの識別情報を検証します。
公開の接続準備情報はHTTPで取得することがありますが、コードや保存済みのアクセス資格を
平文では送信しません。PCで端末ごと、またはまとめて登録を解除でき、対象の操作・映像・音声接続も停止します。

### 公式ブラウザ入口とAndroidアプリの取得

スマホのブラウザは `https://support.nyanreal.jp/spatial-wall/remote/` から、
オープンソースの入口と固定の接続用プログラムを取得します。初回の読込とservice workerの
更新確認では、このサイトへ通信します。サイトのホスティング事業者には、接続元IPアドレスや
ブラウザのHTTPヘッダーなど通常のWebリクエスト情報が届きます。本体PCのLAN通信とは別の経路です。
入口に解析サービスや第三者のスクリプトは含めていません。

Androidのホーム画面にリモコンをインストールすると、ChromeはWebアプリの作成や更新のため、
公開されているアプリ名・アイコン・固定の起動URLをGoogleのWebAPKサービスへ送ることがあります。
これらの情報にPCのアドレス・招待・端末のアクセス資格は含まれません。

入口に必要なファイルをすべて保存すると、次回からオフラインでも開けます。
キャッシュに含めるのは固定の入口ファイルだけで、LANの応答、画面、登録用の招待、
アクセス資格は含めません。実際のリモコン操作画面は接続先PCから取得して検証し、
入口の保存資格から分離して実行します。操作や画面データはそのPCへ直接送られ、
サポートサイトを中継しません。

QRのPCアドレスと短時間有効な招待はURLのフラグメント部分にあり、サイトへのリクエストには
送信されません。入口はモジュールを読み込む前にアドレスバーからフラグメントを取り除きます。
保存したPCアドレス・公開の識別情報・権限・端末のアクセス資格は、そのブラウザプロファイルの
ローカル保存領域に残ります。同じサイトの信頼されたコードや、プロファイルへアクセスできる人は
この保存領域にアクセスできます。個人用プロファイルを使い、不要な端末はPCで登録を解除してください。
ブラウザの保存済みPCの削除だけでは、PC側の登録は解除されません。

Androidアプリをインストールまたは更新するときは、お客様の操作で、ブラウザが登録済みの
Spatial WallのPCからLAN内の認証・暗号化された接続でAPKを取得します。ブラウザは製品署名付きの
配布情報とAPKのサイズ・ハッシュを検証してから保存を案内します。お客様が保存したファイルを
開き、Androidの画面でインストールを確認します。サポートサイトが配信するのはブラウザの入口で、
APKとブラウザの端末資格はブラウザとPCの間で直接やり取りします。
Androidアプリは初回利用時にブラウザとは別にPCに登録します。

AndroidのSpatial Wall用PC登録は、アプリ専用のAndroid Keystore鍵で暗号化し、バックアップから
除外します。PCの公開の識別情報・端末のアクセス資格・権限を含み、表示名とPCアドレスは別の
ローカル設定に保存します。通常の再接続で生体認証は求めません。保存の消去・喪失後は再登録が
必要です。これらは後述のRDP資格ストアとは別に管理します。

### 取り込む情報と、その行き先

ウォールを描くために、本アプリは画面と再生中アプリの音声を取り込み、
メガネから頭の動きを読み取ります。これらはメモリ上で処理され、
USB / DisplayPort でメガネへ、またはブリッジ使用時はご自身の Android 端末へ
ローカルネットワーク経由で送られます。**ディスクには書き出されず、
それ以外のどこにも送信されません。** カメラ・マイクは使用しません。

各ウィンドウを空間に配置するために、OS のウィンドウ一覧（ウィンドウ名と
プロセス名を含みます）を読み取ります。これはお使いのコンピューターの
メモリ上で完結します。

### コンピューターに保存されるデータ

| 保存場所 | 保存内容 |
|---|---|
| `%APPDATA%\nyan-real\`（Windows。Linux / macOS では各 OS のユーザー設定ディレクトリ） | 設定 |
| `%APPDATA%\nyan-real\logs\` | 診断ログ（起動ごとに 1 ファイル） |
| アプリの保護されたリモコン登録領域 | PCの秘密鍵、登録端末の名前・権限・資格の検証用ハッシュ |

ログには、問題を調べられるように、アプリと接続中のメガネの動作（機種、
ファームウェア版数、取り込みと描画の診断情報）が記録されます。ログは
コンピューターの中に留まります。お客様ご自身がサポート依頼に添付した場合に
限り、外部へ渡ります。

「詳細」の「設定を既定値に戻す」で、設定は初期状態に戻ります。
スマホの端末登録は残るため、解除する場合はスマホリモコンの設定で別途操作します。

### 接続したメガネ

USB 経由で、メガネが報告する情報（機種、ファームウェア版数、温度、
センサー値、デバイスに保存された工場校正値）を読み取ります。これらは
正しく描画するために使用し、アプリ内に表示します。外部へ送信することは
ありません。

### Android メガネブリッジの RDP 接続

本通知の他の記述における「本アプリ」は Spatial Wall 本体を指します。
Android メガネブリッジでは、ローカルネットワーク内の PC への直接 RDP 接続が可能です。
**インターネット上の外部サーバーと通信するのは、お客様が指定し、接続操作を行った
RD Gateway に接続する場合のみです。** Spatial Wall 本体の外部通信を行わない方針は
変わりません。RDP は TLS を使用し、接続先 PC と Gateway の証明書を、Android の
システム CA およびお客様が端末に登録した CA に照らして検証します。

指定した PC と、設定した場合は Gateway に、それぞれのログインに必要な認証情報を
送ります。キーボード・ポインター入力を接続先 PC へ送り、その画面と再生音声を受信します。
頭の動きは受信画面の表示のため端末内で処理します。この RDP クライアントでは、
クリップボード・ファイル・ドライブ・マイク・カメラを転送しません。
配布元の中継サーバー、テレメトリ、インターネットサービスへの自動接続はありません。

複数の接続先、アカウント名、保存を選んだパスワードは、アプリ専用のバックアップ対象外
領域に AES-256-GCM で暗号化保存します。暗号鍵は Android Keystore が管理し、
解除と保存のたびに強い生体認証または端末の画面ロック認証を求めます。
アプリが生体情報を受け取ることはありません。接続先管理画面を閉じる、または離れると
登録情報を再びロックします。接続中は資格情報を一時的にメモリで使用し、RDP セッションは
切断するまで継続します。接続先管理画面のロックはリモートセッションのログアウトではありません。

登録情報はバックアップ・端末移行されません。アプリの削除、登録情報の初期化、暗号鍵の
喪失後は再登録が必要です。資格情報は診断ログやリモコンの WebView へ渡しません。
接続先ごとの削除、または「保存済み接続先を初期化」で全登録情報と暗号鍵を削除できます。

### 本通知の変更

本通知を更新した場合、上部の「Last updated」の日付を更新します。

### お問い合わせ

8796n <info@8796.jp> —
https://github.com/8796n/nyan-real-support/issues
