# 06_データベース設計（Database Design）- スキーマ＆物理設計（第55版）

JPAを仲介してデータの一貫性（並行処理・監査証跡）を100%物理保証するための、H2 DatabaseおよびPostgreSQL対応の物理スキーマ設計書です。

---

## 1. 📊 エンティティ関係図（ERD）

```mermaid
erDiagram
    products {
        varchar_50 id PK "商品ID（サニライズ済み型番）"
        varchar_255 card_name "カード名"
        varchar_50 rarity "レアリティ"
        varchar_20 condition "コンディション（MINT / PLAY）"
        varchar_30 storage_shelf "収納棚（A-1~31, B-1~18, S-1~6）"
        int selling_price "販売価格"
        int buying_price "買取価格"
        int stock_qty "現在庫数（レジ・買取が直接変動）"
        bigint version "楽観的ロック用バージョン"
    }

    stock_transactions {
        bigint id PK "トランザクションID"
        varchar_50 product_id FK "商品ID"
        int change_qty "変動数量（正：入庫、負：出庫）"
        varchar_50 reason "変動理由（SALE, BUYBACK, AUDIT_LOSS等）"
        varchar_50 staff_id "操作スタッフID"
        timestamp timestamp "記録日時"
    }

    hold_tasks {
        bigint id PK "保留タスクID"
        varchar_20 task_type "タスク種類（CHECKOUT, BUYBACK）"
        varchar_50 staff_id "保留登録スタッフID"
        text raw_cart_data "お買い物・買取カート内データ（JSON文字列）"
        timestamp held_at "保留日時"
    }

    audit_tasks {
        bigint id PK "棚卸タスクID"
        text target_shelves "棚卸対象棚リスト（JSON文字列）"
        varchar_20 status "ステータス（IN_PROGRESS, COMPLETED）"
        timestamp created_at "タスク開始日時"
        timestamp completed_at "監査確定日時"
    }

    audit_items {
        bigint id PK "棚卸明細ID"
        bigint task_id FK "棚卸タスクID"
        varchar_50 product_id FK "商品ID"
        int expected_qty "開始時のスナップショット期待値"
        int actual_qty "スタッフによるカウント実数（未カウントはNULL、確定時0枚調停）"
    }

    products ||--o{ stock_transactions : "履歴追跡"
    products ||--o{ audit_items : "監査検証"
    audit_tasks ||--|{ audit_items : "包含"
```

---

## 2. 📋 物理テーブル・定義仕様

### 2-1. `products`（商品・リアルタイム在庫マスタ）
※店舗の全在庫を格納するコアテーブル。競合を防ぐための `version` カラムによる楽観的ロックを定義。

| 論理物理カラム名 | 物理名 | データ型 | 制約 | 説明 |
| :--- | :--- | :--- | :--- | :--- |
| **商品ID (PK)** | `id` | VARCHAR(50) | PRIMARY KEY | サニライズ済み型番（例: `SV8-136-106-MINT`） |
| **カード名** | `card_name` | VARCHAR(255) | NOT NULL | カードの日本語正式名称 |
| **レアリティ** | `rarity` | VARCHAR(50) | NOT NULL | SAR, UR, SR, R, C などのレア度 |
| **コンディション** | `condition` | VARCHAR(20) | NOT NULL | `MINT`（美品） / `PLAY`（傷あり・プレイ用） |
| **収納棚** | `storage_shelf` | VARCHAR(30) | NOT NULL | A-1〜31, B-1〜18, S-1〜6（ショーケース） |
| **販売価格** | `selling_price` | INT | NOT NULL | 実際の店頭での販売価格 |
| **買取価格** | `buying_price` | INT | NOT NULL | 現在設定されている買取価格 |
| **現在庫数** | `stock_qty` | INT | NOT NULL | レジ会計・買取入庫でミリ秒単位でリアルタイム変動 |
| **バージョン** | `version` | BIGINT | NOT NULL | JPA楽観的ロック用（初期値 `0`、更新毎に `+1`） |

*   **物理インデックス設計**：
    *   `idx_products_shelf` ON `storage_shelf`：棚卸し時の特定棚の抽出、および品出しの高速化。
    *   `idx_products_filter` ON (`card_name`, `rarity`, `condition`)：在庫一覧の3連スマートフィルタのレスポンス高速化。

---

### 2-2. `stock_transactions`（在庫変動・監査履歴）
※すべての在庫変動の「誰が・いつ・なぜ」を履歴として完璧に追跡するためのテーブル（追記専用）。

| 論理物理カラム名 | 物理名 | データ型 | 制約 | 説明 |
| :--- | :--- | :--- | :--- | :--- |
| **履歴ID (PK)** | `id` | BIGINT | AUTO_INCREMENT | 自動発番される一意のID |
| **商品ID (FK)** | `product_id` | VARCHAR(50) | FOREIGN KEY | `products(id)` への参照 |
| **変動数量** | `change_qty` | INT | NOT NULL | `+2`（買取）、`-1`（販売）、`-3`（棚卸紛失）等 |
| **変動理由** | `reason` | VARCHAR(50) | NOT NULL | `SALE`, `BUYBACK`, `AUDIT_LOSS`, `DISCARD` 等 |
| **操作スタッフID**| `staff_id` | VARCHAR(50) | NOT NULL | 処理を実行したスタッフの認証ID |
| **記録日時** | `timestamp` | TIMESTAMP | DEFAULT CURRENT | 変動が発生した日時（1ミリ秒精度） |

*   **物理インデックス設計**：
    *   `idx_tx_product_date` ON (`product_id`, `timestamp` DESC)：在庫一覧で「商品名をタップした際、そのカードだけの変動履歴を最新順でポップアップ表示する（個別ドリルダウン）」クエリを極限まで高速化。
