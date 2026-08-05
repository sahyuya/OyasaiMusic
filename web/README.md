# OMMT — OyasaiMusicMidiTranslator

MIDIファイルをブラウザ内で解析し、OyasaiMusic専用の`.oyasai`ファイルへ変換する静的Webアプリです。MIDIファイルや変換内容を外部サーバーへ送信しません。

## 主な機能

- Standard MIDI File Type 0 / 1
- テンポ変更を含む絶対発音時刻の計算
- General MIDI音色・打楽器から音ブロック16楽器への変換
- 音域外ノートのオクターブ移動、削除、端への固定
- MIDIベロシティ、チャンネル音量、Expression、Panの反映
- ピアノロール上のクリック・ドラッグ選択
- 時刻範囲と音域を組み合わせた選択
- 選択した1音または複数音を新しい出力パートへ分割
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
