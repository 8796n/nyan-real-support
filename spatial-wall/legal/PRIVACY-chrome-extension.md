# Privacy Policy — nyan Real Audio Wall connector

**Last updated: 2026-09-25**

This is the privacy policy for the **nyan Real Audio Wall connector** browser
extension for Chrome and Edge. It does not cover Spatial Wall itself.

---

## English

### Overview

nyan Real Audio Wall connector is a browser extension that captures the audio of
playing tabs and sends it, together with each window's on-screen position, to
Spatial Wall for Windows, Linux, or macOS running on the **same computer**. Each browser window is then heard
from its position on the virtual screen.

**Audio and stream metadata are processed locally and sent only to an app on
your own computer over `ws://127.0.0.1`. We do not receive your audio or stream
metadata. Chrome may sync the port setting when Chrome Sync is enabled, as
described below.**

### Audio capture

The extension hooks eligible `<audio>/<video>` elements with Web Audio to capture
their PCM audio for spatialization. DRM media and cross-origin media without CORS
are not hooked and continue normal local playback.

- Audio is sent **only** to the local nyan Real app at `ws://127.0.0.1:<port>`
  (default 8796) on the same machine.
- The extension does not send audio to an external server, cloud service, or
  third party, and does not save audio recordings to disk.
- No camera or microphone is used.

### Local connection only

The extension connects only to `127.0.0.1` (localhost). It opens no remote
network connection and is not reachable from outside your machine.

### Data stored locally

The extension stores a single setting using Chrome's built-in storage. No
external database or server is involved.

| Storage | What is stored |
|---|---|
| `chrome.storage.sync` | The WebSocket port number |

**`chrome.storage.sync` note:** if you are signed into Chrome with Chrome Sync
enabled, Chrome itself may sync this setting across your devices through Google's
infrastructure. This is handled entirely by Chrome and Google; the extension does
not initiate or control it. See
[Google's Privacy Policy](https://policies.google.com/privacy).

### Tab information

The extension reads tab titles through the `chrome.tabs` API (access supplied by
the `<all_urls>` host permission) to label each audio stream in the local app.
Up to the first 80 characters of each title are sent to that app together with
playback state, browser name, window position, and the audio format. The
extension does not send this information to external servers or save it to disk.
Page and media URLs are used locally to determine whether media can be captured;
they are not included in the audio stream metadata.

### Display information

The extension reads the monitor layout (via the `system.display` permission) only
to map a window's on-screen position to a spatial direction. This information
stays local.

### Hidden audio transport document

The `offscreen` permission hosts a dedicated audio transport Worker in a hidden
extension document. It forwards the same audio and metadata to the local app;
it does not capture the screen or use the microphone. The document closes after
the last streaming page disconnects and a short grace period expires.

### Host permission (all URLs)

The content script runs on supported pages because audio can play on many
websites. It hooks eligible media elements for spatialization and uses tab
titles as described above. It does not send audio or page information to
external servers.

### No analytics or tracking

The extension does **not** include analytics, telemetry, crash reporting,
advertising, tracking pixels, or any third-party SDK that collects data.

### Third-party libraries

The extension bundles no third-party libraries or SDKs. It uses only standard
browser APIs (Web Audio, WebSocket, and the `chrome.*` extension APIs).

### Changes to this policy

If this policy is updated, the "Last updated" date above will be revised.
Continued use of the extension after changes constitutes acceptance of the
updated policy.

### Contact

For questions or concerns about this privacy policy, please open an issue at:
https://github.com/8796n/nyan-real-support/issues

---

## 日本語

### 概要

nyan Real Audio Wall connector は、再生中のタブの音声を取り込み、各ウィンドウの
画面上の位置とあわせて、**同じコンピューター上**で動作する nyan Real へ送信する
ブラウザ拡張機能です。対応する受け口はSpatial WallのWindows / Linux / macOS版です。
これにより、ブラウザの各ウィンドウが仮想
スクリーン上の位置から聞こえます。

**音声とストリーム情報はローカルで処理し、`ws://127.0.0.1`を通じて、お使いの
コンピューター上のアプリにのみ送信します。開発者はこれらを受信しません。
Chrome同期が有効な場合、後述のとおりポート設定がChromeによって同期されることがあります。**

### 音声の取り込み

本拡張は、対象となる `<audio>/<video>` 要素を Web Audio でフックし、空間化のために
PCM 音声を取り込みます。DRM メディアと CORS 指定のないクロスオリジンメディアは
フックせず、通常どおりローカル再生を続けます。

- 音声は同一マシン上のローカル nyan Real アプリ `ws://127.0.0.1:<port>`（既定
  8796）へ**のみ**送信されます。
- 本拡張は音声を外部サーバー・クラウドサービス・第三者へ送信せず、録音として
  ディスクへ保存することもありません。
- カメラ・マイクは使用しません。

### ローカル接続のみ

本拡張は `127.0.0.1`（localhost）にのみ接続します。リモートへのネットワーク接続は
行わず、マシンの外部から到達できるサーバーにもなりません。

### ローカルに保存されるデータ

本拡張は Chrome の組み込みストレージを使って 1 つの設定のみを保存します。外部
データベースやサーバーは一切使用しません。

| ストレージ | 保存内容 |
|---|---|
| `chrome.storage.sync` | WebSocket ポート番号 |

**`chrome.storage.sync` について:** Chrome にサインインし Chrome 同期が有効な場合、
Chrome 自体がこの設定を Google のインフラを通じて複数デバイス間で同期することが
あります。これは Chrome および Google が行うもので、本拡張が主体的に行うもの
ではありません。詳細は
[Google のプライバシーポリシー](https://policies.google.com/privacy)をご確認
ください。

### タブ情報

本拡張は、`<all_urls>` ホスト権限で許可された `chrome.tabs` API を通じてタブの
タイトルを読み取り、ローカルアプリで各音声を識別するために使用します。
タイトルの先頭80文字までを、再生状態、ブラウザ名、ウィンドウ位置、音声形式とともに
ローカルアプリへ送ります。本拡張はこれらを外部サーバーへ送信せず、ディスクへ保存しません。
ページやメディアのURLは、音声を取り込めるかを判断するためにローカルで参照し、
音声ストリームのメタデータには含めません。

### ディスプレイ情報

本拡張は（`system.display` 権限で）モニタ配置を読み取りますが、これはウィンドウの
画面上の位置を空間定位の方向にマップするためのみに使用します。この情報はローカルで
完結します。

### 音声送信の非表示ドキュメント

`offscreen`権限で拡張の非表示ドキュメントに音声送信専用Workerを置き、前述の音声と
ストリーム情報をローカルアプリへ送ります。画面の取り込みやマイクの使用は行いません。
最後の送信ページが切断され、短い待機時間が過ぎると、このドキュメントを閉じます。

### ホスト権限（全 URL）

さまざまなウェブサイトの音声を空間化するため、対応ページでコンテンツスクリプトを
動かし、対象のメディア要素から音声を取り込みます。タブのタイトルも前述の目的で
使用します。音声やページ情報を外部サーバーへ送信することはありません。

### アナリティクス・トラッキングなし

本拡張には、アナリティクス、テレメトリ、クラッシュレポート、広告、トラッキング
ピクセル、データを収集するサードパーティ SDK は一切含まれません。

### サードパーティライブラリ

本拡張はサードパーティのライブラリや SDK を一切同梱していません。標準のブラウザ
API（Web Audio、WebSocket、`chrome.*` 拡張 API）のみを使用しています。

### ポリシーの変更

このポリシーを更新した場合、上部の「Last updated」日付を更新します。変更後も拡張
機能を継続して使用することで、更新されたポリシーへの同意とみなします。

### お問い合わせ

このプライバシーポリシーに関するご質問・ご意見は、以下の GitHub Issues までお寄せ
ください：
https://github.com/8796n/nyan-real-support/issues
