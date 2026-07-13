package com.github.sahyuya.oyasaiMusic.audio

/**
 * 音の送信方式。
 *
 * - [ENTITY_EMITTER] (デフォルト): Adventure APIの `Sound.Emitter.self()`
 *   （内部的には `ClientboundSoundEntityPacket` を使用）で、音源をプレイヤー自身に追従させる。
 *   Panは扱わない。プレイヤーが移動しても音源が追従するため、移動による音響の乱れが起きない。
 * - [POSITIONAL]: 従来の `Location` + Pan方式（プレイヤー正面を基準とした半円配置でのステレオ定位）。
 *   「高音質版」のオプション再生として提供する。発音した瞬間のプレイヤー位置・向きを基準に
 *   仮想音源を配置するため、移動しながら聴くとPanがズレて感じられることがある。
 */
enum class PlaybackMode {
    ENTITY_EMITTER,
    POSITIONAL,
}
