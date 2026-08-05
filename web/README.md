# OMMT — OyasaiMusicMidiTranslator

MIDIファイルをブラウザ内で解析し、OyasaiMusic専用の`.oyasai`ファイルへ変換する静的Webアプリです。MIDIファイルや変換内容を外部サーバーへ送信しません。

編集状態も配信元へ送信せず、同じブラウザプロフィールのIndexedDBへ最新セッションを1件保存します。同じ端末・同じブラウザではページを閉じても復元できますが、別端末・別ブラウザとの同期やアカウント共有は行いません。

## 主な機能

- Standard MIDI File Type 0 / 1
- テンポ変更を含む絶対発音時刻の計算
- General MIDI音色・打楽器から音ブロック16楽器への変換
- 音域外ノートのオクターブ移動、削除、端への固定
- MIDIベロシティ、チャンネル音量、Expression、Panの反映
- ピアノロール上のクリック・ドラッグ選択
- 時刻範囲と音域を組み合わせた選択
- 選択した1音または複数音を新しい出力パートへ分割
- 複数MIDIの同時開始での重ね合わせ、または末尾への連結
- 全パート重ね合わせ表示と、パートごとの個別編集表示
- 選択ノートの時間グリッド移動、半音移動、正確な時刻・音程編集、削除
- 横方向・縦方向のピアノロール拡大縮小
- 全音階の横線と、Minecraft音ブロック音域に合わせたF♯目盛り
- 拍グリッドへ補正した範囲選択
- シークバーから任意位置を指定した試聴
- IndexedDBへの端末内自動保存と、再訪時の編集状態復元
- 変換後の簡易試聴
- OyasaiMusic専用`.oyasai`ファイルの生成

## ローカル確認

Node.js 22以降を使用します。外部パッケージのインストールは不要です。

```text
npm run dev
```

表示されたローカルURLをブラウザで開きます。

## テストと静的ビルド

```text
npm test
npm run build
```

GitHub Pagesへ公開するファイルは`dist`へ生成されます。リポジトリの`.github/workflows/pages.yml`は、`master`ブランチの`web`変更をテスト・ビルドしてPagesへ公開します。

## Minecraftへの取り込み

1. OMMTで`.oyasai`をダウンロードします。
2. ファイルを`plugins/OyasaiMusic/import`へ入れます。
3. `oyasaimusic.use`と`oyasaimusic.import`権限を持つプレイヤーが`/mm import <ファイル名.oyasai>`を実行します。
4. 曲は実行者を作者とする非公開下書きとして保存されます。
5. 曲名、参考URL、レコード、価格、公開状態はOyasaiMusicの設定画面で変更します。

インポート済みのファイルは、通常`plugins/OyasaiMusic/import/processed`へ移動します。

OMMTサイト側には曲長・ファイルサイズ・ノート数の固定上限を設けていません。現在のOyasaiMusic音源リーダーには技術上の上限として1,000,000ノートがあるため、それを超える`.oyasai`はサーバーへの保存前に理由付きで拒否されます。

## 利用上の注意

OMMTはOyasaiMusic専用ツールであり、Minecraftの公式製品ではありません。利用・公開・配布にあたっては、Minecraftの利用規約およびガイドライン、MIDI楽曲の権利、サーバーの規則に従ってください。
