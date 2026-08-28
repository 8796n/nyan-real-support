# Privacy Notice — nyan Real / Spatial Wall (standalone app)

**Last updated: 2026-08-19**

This is the privacy notice for the **standalone nyan Real / Spatial Wall
application** (`spatial-wall.exe` on Windows, `spatial-wall` on Linux and
macOS). It does not cover the OBS Studio plugin, nor the browser extension —
the extension has its own
[privacy notice](https://github.com/8796n/obs-nyan-real-3dof/blob/main/PRIVACY.md).

---

## English

### Summary

**The app contacts no server on the internet.** It makes no update check and
reports no usage or crash data. Everything it
processes — screen images, audio, head tracking — stays on your own computer or
inside your own network.

### No external communication

The app contains no analytics, telemetry, crash reporting, advertising or
third-party SDK of any kind. There is no automatic update check and no account,
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

The phone remote, companion APK download, and Android bridge use unencrypted
HTTP/WebSocket inside that network. Use them only on a trusted private network,
not shared or public Wi-Fi.

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

Logs record what the app and the connected glasses did — device model, firmware
version, capture and rendering diagnostics — so a problem can be investigated.
They stay on your computer. They are only shared if you attach one to a support
request yourself.

"Advanced" > "Reset settings to defaults" returns the settings to their initial
state.

### Connected glasses

Over USB the app reads what the glasses report — model, firmware version,
temperature, sensor data, and the factory calibration stored in the device. This
is used to render correctly and is shown in the app. It is not transmitted
anywhere.

### Changes to this notice

If this notice is updated, the "Last updated" date above will be revised.

### Contact

8796n <info@8796.jp> —
https://github.com/8796n/nyan-real-spatial-wall/issues

---

## 日本語

### 要約

**インターネット上の外部サーバーとは通信しません。** 更新の自動確認も、
利用状況やクラッシュ情報の送信も行いません。処理する
情報（画面の映像、音声、頭の動き）は、お使いのコンピューターの中か、
ご自身のネットワークの中で完結します。

### 外部通信を行わないこと

本アプリには、アナリティクス、テレメトリ、クラッシュレポート、広告、
サードパーティ SDK は一切含まれません。更新の自動確認もなく、アカウント、
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

スマホリモコン、連携用 APK のダウンロード、Android ブリッジは、その
ネットワーク内で暗号化されていない HTTP / WebSocket を使用します。共有・公衆 Wi-Fi
ではなく、信頼できる私設ネットワークでのみお使いください。

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

ログには、問題を調べられるように、アプリと接続中のメガネの動作（機種、
ファームウェア版数、取り込みと描画の診断情報）が記録されます。ログは
コンピューターの中に留まります。お客様ご自身がサポート依頼に添付した場合に
限り、外部へ渡ります。

「詳細」の「設定を既定値に戻す」で、設定は初期状態に戻ります。

### 接続したメガネ

USB 経由で、メガネが報告する情報（機種、ファームウェア版数、温度、
センサー値、デバイスに保存された工場校正値）を読み取ります。これらは
正しく描画するために使用し、アプリ内に表示します。外部へ送信することは
ありません。

### 本通知の変更

本通知を更新した場合、上部の「Last updated」の日付を更新します。

### お問い合わせ

8796n <info@8796.jp> —
https://github.com/8796n/nyan-real-spatial-wall/issues
