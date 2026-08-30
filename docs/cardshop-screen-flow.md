# 07_画面遷移図（Screen Transition Diagram）- 業務フロー直結型（第55版）

タブレットを片手に動き回る店舗スタッフの「コンテキストスイッチ（作業の突発的な割り込みと切り替え）」を物理的にガードし、ミスを防ぐための全画面遷移図です。

---

## 1. 🗺️ 画面遷移マップ（Mermaid）

```mermaid
flowchart TD
    %% ----------------------------------------------------
    %% Screen Nodes (1.5x Touch UI Layouts)
    %% ----------------------------------------------------
    INDEX["📋 商品・在庫一覧 (index.html)<br/>(3連スマートフィルタ / 履歴ドリルダウン)"]
    CHECKOUT["🛒 販売レジ (checkout.html)<br/>(カート・数量マージ・値引処理)"]
    BUYBACK["💰 買取査定 (buyback.html)<br/>(一括入力・Base64画像保存)"]
    REGISTER["➕ 新規カード登録 (register.html)<br/>(SoC: マスタと在庫の完全分離)"]
    STOCK["📦 在庫入出庫・操作 (stock.html)<br/>(スタッフID＆理由の強制入力)"]
    AUDIT["📋 分割棚卸し (audit.html)<br/>(55棚マルチ選択 / ブラインドマスク)"]
    ALERTS["🚨 高額・監査ログ (alerts.html)<br/>(3万円以上レジ・3千円以上廃棄)"]
    CONFIG["⚙️ システム設定 (config.html)<br/>(54棚マスタ管理 / 認証PIN設定)"]

    %% ----------------------------------------------------
    %% Global Nav & Polling Mechanisms
    %% ----------------------------------------------------
    subgraph GlobalTray ["🔄 双方向・ワンタップ保留タスクトレイ"]
        TRAY_STATE["localStorage / Polling 同期<br/>(保留中のカート・査定データ)"]
    end

    %% ----------------------------------------------------
    %% Page Transitions (Bold arrows represent high frequency)
    %% ----------------------------------------------------
    INDEX ====>|1. クリック| CHECKOUT
    INDEX ====>|1. クリック| BUYBACK
    INDEX -->|カード名タップ| INDIVIDUAL_LOG["🔍 個別履歴ドリルダウンモーダル"]
    
    %% Context Switch (High Frequency Real-World Workflow)
    BUYBACK ====>|「会計割り込み」発生| GlobalTray
    GlobalTray ====>|買取データを自動退避 (1秒)| CHECKOUT
    CHECKOUT ====>|お会計完了| GlobalTray
    GlobalTray ====>|退避データを読み込み再開| BUYBACK

    %% SoC & Redirect Guards
    REGISTER -->|重複登録エラー| DLG_REPOST{"⚠️ 登録重複ダイアログ"}
    DLG_REPOST -->|「在庫を品出し加算」を選択| STOCK
    STOCK -->|履歴保存完了| INDEX

    %% Operations Flow
    INDEX --> AUDIT
    AUDIT -->|紛失・差異を自動計算| INDEX
    CHECKOUT -->|3万円以上の高額会計が発生| ALERTS
    STOCK -->|3千円以上 or 「その他」理由の廃棄| ALERTS
    
    %% Config Transition
    INDEX --> CONFIG
    CONFIG -->|PINコード・棚マスタの変更| INDEX
    
    classDef main fill:#f9f,stroke:#333,stroke-width:2px;
    classDef tray fill:#bbf,stroke:#f66,stroke-width:2px;
    class INDEX,CHECKOUT,BUYBACK,AUDIT main;
    class GlobalTray,TRAY_STATE tray;
```

---

## 2. 🛡️ 画面遷移におけるミス防止ガードレール（実務上のこだわり）

### 2-1. レジと買取のコンテキストスイッチ（突発的な「レジ応援」対策）
*   **現場の課題**：買取査定中（お客様のカードを数枚チェックしている最中）にレジが混み合い、「レジ応援」に走らざるを得ない瞬間が多発します。この時、ブラウザをリロードしたり戻るボタンを押すと、入力途中のデータが完全に揮発してしまいます。
*   **システムのガード**：
    すべての画面のヘッダーに配置された「保留タスクトレイ」は、バックグラウンドで `localStorage` を監視しています。査定画面からレジ画面へ切り替える際、**1タップで現在の査定データが自動的に「保留データ」として退避**され、レジ画面でのお会計が終わり次第、トレイから即座にワンタップで元の状態へ100%復元できます。

### 2-2. 重複マスタの乱立を防ぐ「登録重複リダイレクト」
*   **現場の課題**：新人が「すでにマスタに登録されているカード」を、そうとは知らずに「新規商品登録画面（`register.html`）」から二重に登録してしまい、データベースが重複データで汚れるバグが多発します。
*   **システムのガード**：
    `register.html` で既存の型番（主キー）が送信された場合、システムは「登録エラー（HTTP 500）」を吐いてクラッシュするのではなく、**「すでにそのカードは登録されています。マスタ価格を保護したまま、今回入力された数量だけ在庫に加算しますか？」というモーダル**を表示。これを許可すると、自動的に「在庫調整画面（`stock.html`）」へデータを持ったままリダイレクト（ディープリンク）し、マスタの汚染を物理的に100%防ぎます。
