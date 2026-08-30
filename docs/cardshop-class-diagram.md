# 05_クラス図（Class Diagram）- Spring Boot 構成仕様（第55版）

JPAのデータ一貫性保証、およびVanilla JSフロントエンドとのRESTful通信を統制する、Spring Bootバックエンド（MVC三層アーキテクチャ）の厳密な設計クラス図です。

---

## 1. 🧬 クラス依存関係図（Mermaid）

```mermaid
classDiagram
    direction TB

    %% ----------------------------------------------------
    %% Controllers (Web/API Layer)
    %% ----------------------------------------------------
    class ProductController {
        -ProductService productService
        +getProductList(filter) List~Product~
        +registerProduct(productDto) ResponseEntity
        +getProductHistory(productId) List~StockTransaction~
    }
    
    class OperationsController {
        -OperationsService operationsService
        +holdTask(holdTaskDto) ResponseEntity
        +restoreTask(taskId) HoldTaskDto
        +checkout(cartDto) ResponseEntity
        +buyback(buybackDto) ResponseEntity
    }
    
    class AuditController {
        -AuditService auditService
        +startAudit(auditRangeDto) AuditTask
        +submitAudit(taskId, auditItems) ResponseEntity
    }

    %% ----------------------------------------------------
    %% Services (Business Logic Layer)
    %% ----------------------------------------------------
    class ProductService {
        -ProductRepository productRepository
        -StockTransactionRepository txRepository
        +findWithFilter(filter) List~Product~
        +saveProductAndSanitize(Product) Product
        +getTransactionHistory(productId) List~StockTransaction~
    }
    
    class OperationsService {
        -ProductRepository productRepository
        -HoldTaskRepository holdRepository
        -StockTransactionRepository txRepository
        +createHold(HoldTask) HoldTask
        +processCheckout(Cart) void
        +processBuyback(Buyback) void
    }
    
    class AuditService {
        -AuditTaskRepository taskRepository
        -AuditItemRepository itemRepository
        -ProductRepository productRepository
        -StockTransactionRepository txRepository
        +initializeAuditTask(AuditRange) AuditTask
        +executeRelativeReconciliation(taskId, Map~productId, actualQty~) void
    }

    %% ----------------------------------------------------
    %% Repositories (Data Access Layer)
    %% ----------------------------------------------------
    class ProductRepository {
        <<interface>>
        +findByIdWithLock(id) Optional~Product~
        +findByShelfAndTitleAndRarity() List~Product~
    }
    
    class HoldTaskRepository {
        <<interface>>
        +findByStatusActive() List~HoldTask~
    }
    
    class AuditTaskRepository {
        <<interface>>
        +findActiveTasks() List~AuditTask~
    }

    %% ----------------------------------------------------
    %% Entities (Domain Models with JPA)
    %% ----------------------------------------------------
    class Product {
        -String id <<PK>> (Sanitized String)
        -String cardName
        -String rarity
        -String condition (MINT/PLAY)
        -String storageShelf (A-1~31, B-1~18, S-1~6)
        -Integer sellingPrice
        -Integer buyingPrice
        -Integer stockQty
        -Long version <<Version>> (Optimistic Lock)
        +updateStock(diff) void
    }
    
    class StockTransaction {
        -Long id <<PK>>
        -String productId
        -Integer changeQty
        -String reason (SALE, BUYBACK, AUDIT_LOSS, etc)
        -String staffId
        -LocalDateTime timestamp
    }
    
    class HoldTask {
        -Long id <<PK>>
        -String taskType (CHECKOUT, BUYBACK)
        -String staffId
        -String rawCartData (JSON text)
        -LocalDateTime heldAt
    }
    
    class AuditTask {
        -Long id <<PK>>
        -String targetShelves (JSON text)
        -String status (IN_PROGRESS, COMPLETED)
        -LocalDateTime createdAt
        -LocalDateTime completedAt
    }
    
    class AuditItem {
        -Long id <<PK>>
        -Long taskId <<FK>>
        -String productId <<FK>>
        -Integer expectedQty
        -Integer actualQty
    }

    %% ----------------------------------------------------
    %% Relations
    %% ----------------------------------------------------
    ProductController --> ProductService : DI
    OperationsController --> OperationsService : DI
    AuditController --> AuditService : DI

    ProductService --> ProductRepository : DI
    OperationsService --> ProductRepository : DI
    OperationsService --> HoldTaskRepository : DI
    AuditService --> AuditTaskRepository : DI
    AuditService --> ProductRepository : DI

    ProductRepository ..> Product : Manages
    HoldTaskRepository ..> HoldTask : Manages
    AuditTaskRepository ..> AuditTask : Manages

    AuditTask "1" *-- "many" AuditItem : Composition
    AuditItem --> Product : References
    StockTransaction --> Product : References
```

---

## 2. 🏗️ クラス設計・アーキテクチャの解説

### 2-1. 疎結合の徹底（Web・ドメイン・データの三層分離）
*   **Controller層**：HTTPリクエストの受付、例外のハンドリング、およびDTOからEntityへの変換に特化させ、ビジネスロジックを一切持たせません。
*   **Service層**：すべてのビジネスルール、差分補正計算、およびトランザクション境界（`@Transactional`）を管轄します。
*   **Repository層**：Spring Data JPAを継承し、JPAプロバイダ（Hibernate）を介して安全なクエリを発行します。

### 2-2. データ整合性のための「ガードレール設計」
1.  **楽観的ロック（Optimistic Locking）**
    *   `Product` エンティティに `@Version` フィールド（`version`）を定義。
    *   在庫更新時の競合（ロストアップデート）を検知すると `ObjectOptimisticLockingFailureException` をスローし、データを自動保護します。
2.  **サニタイズ処理の委譲（SoC）**
    *   `ProductService` 内で、登録される商品ID（トレカ型番）のスラッシュをハイフンに置換（サニタイズ）してから永続化。セキュリティ上の脆弱性をサーバー境界で完全にブロックします。
