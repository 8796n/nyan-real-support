# Spatial Wall ブラウザ入口のソース

[English](README.md)

このディレクトリには MIT 許諾のブラウザ入口、ペアリング用 WebAssembly のソース、
それらを再ビルドする道具を元と同じ相対パスで収めています。
実行ファイルを配る `remote/` とは分けて公開します。サービスワーカーは
このソース一式をダウンロード・キャッシュしません。

ブラウザ入口は LAN 内 PC を認証し、親ページ側で端末の資格情報を保持します。
PC から受け取る署名済みリモコン UI は分離した iframe で動かします。
PC 側 UI、デスクトップ本体、製品署名秘密鍵、保存済み端末情報はこの配布に含みません。
`data/remote-loader/product-key.pem` は署名検証用の公開鍵です。

利用者が選ぶと、入口は接続中の PC から Android アプリも取得します。APK の配布情報にある
製品署名と、ダウンロードしたファイル全体のハッシュを検証してから保存できるようにします。
APK は PC アプリに同梱されます。

## 含まれるもの

- `data/remote-loader/`: 加工前の JavaScript・HTML・CSS、サービスワーカーの雛形、
  ホーム画面用アイコン、検証用公開鍵、MIT 許諾全文。
- `src/core/remote_pairing/` と `src/core/ws_message_validation.h`: 共通 C++ ペアリングと、
  既存の静的ライブラリをコンパイルする補助ヘッダー。
  Wasm の公開 API はペアリングだけで、未使用のサーバー用 object は link されません。
- `tools/remote-pairing-wasm/`: Wasm の入口、native 相互試験、ビルド補助、
  固定 runtime manifest、許諾検査、ビルド補助が実行する試験。
- `scripts/`: 固定 BoringSSL の導入と Windows toolchain の検出。
- `tools/remote-ui/`: 配信用 stage と公開ソース許可リストの検査。
- `licenses/`: BoringSSL と link される Wasm runtime の許諾・著作者表示全文。

## Windows での再ビルド

このディレクトリ全体を相対パスを保って取得し、以下はそのルートで実行します。
デスクトップ本体のソース、Android SDK、アカウント情報、製品秘密鍵は不要です。
現行の補助スクリプトは Windows x64、PowerShell、Git、Python 3.11 以降、Node.js 24、
Visual Studio 2026 の C++ tools（MSVC 14.51 以降、CMake/Ninja component、
Windows SDK 10.0.26100.0 以降）を使用します。

まずソースの不足を検査し、固定 BoringSSL を取得します。

```powershell
python tools/remote-ui/source-loader.py --check
python tools/remote-ui/test-source-loader.py
python scripts/setup-remote-pairing.py
```

固定 revision と notice の hash は `src/core/remote_pairing/boringssl.json` にあります。
setup は変更済みの依存を拒否し、同梱 notice を検査します。

公式 Emscripten SDK の固定版を専用のローカルディレクトリへ導入します。

```powershell
git clone https://github.com/emscripten-core/emsdk.git deps/emsdk-spake2-probe
git -C deps/emsdk-spake2-probe checkout --detach d7b4130688bfa348392b921ab04015aa15c4f154
$env:EMSDK_KEEP_DOWNLOADS = '1'
Push-Location deps/emsdk-spake2-probe
try { python emsdk.py install releases-eb132757325583b787fe9414b09afea9442b72ad-64bit }
finally { Pop-Location; Remove-Item Env:EMSDK_KEEP_DOWNLOADS }
```

Emscripten は 6.0.13、compiler revision は
`e13ee988ce3d87a738dd8f3661d02223308c412a` です。
固定の [公式 Windows archive](https://storage.googleapis.com/webassembly/emscripten-releases-builds/win/eb132757325583b787fe9414b09afea9442b72ad/wasm-binaries.zip)
は 701,262,388 bytes、展開後は約1.94 GBです。
ビルド前に保持された archive を `Get-FileHash -Algorithm SHA256 <archive-path>` で検査し、
SHA-256 が次と一致することを確認します。
`937b834b397e33645923794c1a20566a86c63ebc02b213e537c0a86dd380ab6c`。
これは公式 HTTPS 配布物を取得して記録した hash で、上流が別途公開した checksum との
照合ではありません。同じ値と runtime notice の hash は
`tools/remote-pairing-wasm/runtime.json` にあります。
SDK の activate や global PATH の変更は不要です。ビルド補助はローカルの
`EM_CONFIG` を使用し、終了時に元の値へ戻します。

```powershell
& tools/remote-pairing-wasm/build.ps1
python tools/remote-ui/stage-loader.py
python tools/remote-ui/stage-loader.py --check
```

既存の共通 C++ core を native と Wasm の双方へビルドし、両 role の相互試験、
誤ったコード・context・MAC・proof の拒否、実際に link した runtime の許諾を検査します。
その後で入力・出力の hash を記録します。stage は変更済みソースや出力を拒否し、
`build/remote-loader/site/` に内容の hash で識別する配信用ファイルを作ります。
生成物は `build/`、取得した依存は `deps/` 内に限定します。これらのコマンドは公開しません。

2026-11-05、公開ソース（`0429a18`）を独立したディレクトリへ置き、
デスクトップ本体のソースや秘密鍵を補わずに native と Wasm を新規ビルドできました。
検査済みの固定依存キャッシュを再利用し、ソース検査8件、native/Wasmの153 assertion、
notice検査3件、配信用stageの生成・照合が成功しました。
依存の新規ダウンロードや、未導入のWindows環境へのtoolchain設定はこの試験に含みません。

再ビルドした配信内容25ファイルのうち24ファイルは公開版とbyte単位で一致しました。
WasmにはBoringSSLの絶対ビルドパスが含まれ、サイズ・SHA-256は異なりました
（独立ディレクトリでは94,588 bytes、公開版は94,482 bytes）。
動作を含めた再ビルドは確認済みですが、Wasmのbit単位の再現性は保証していません。

## 変更した入口の実行

開発時は stage の内容を secure origin の `/spatial-wall/remote/` で配信します。
Web アプリの manifest は、このパスをインストール時の識別子と起動先に使います。加工前の `index.html` を
`file://` で開く方式は使用しません。現行製品は公式 origin
`https://support.nyanreal.jp` を許可しているため、別ホストで運用するには
PC 側の origin policy も意図して変更する必要があります。
このソースをビルドするだけで、別 origin に既存 PC の操作権限が与えられるわけではありません。
公式入口の対象は対応 Chromium ブラウザで、iOS ブラウザには対応していません。

## 許諾

この配布内の自作ソースは同梱の [MIT 許諾](LICENSE) です。
依存の許諾は [BoringSSL](licenses/boringssl.txt) と
[Wasm runtime](licenses/remote-pairing-wasm.txt) を参照してください。
上流の著作者表示・LLVM 例外も含めて、ビルドの配布時に保持します。
デスクトップ本体には別の使用許諾が適用されます。
