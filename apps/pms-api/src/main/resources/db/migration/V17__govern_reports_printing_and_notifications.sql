-- G8: governed report definitions, persisted asynchronous artifacts, receipt printing and notification simulation.

CREATE TABLE report_definition (
    id VARCHAR(36) PRIMARY KEY,
    report_code VARCHAR(80) NOT NULL,
    page_path VARCHAR(180) NOT NULL,
    title VARCHAR(160) NOT NULL,
    row_grain VARCHAR(240) NOT NULL,
    formula_note VARCHAR(1000) NOT NULL,
    formula_json JSON NOT NULL,
    columns_json JSON NOT NULL,
    fixed_sample_json JSON NOT NULL,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_report_definition_code UNIQUE (report_code),
    CONSTRAINT uk_report_definition_path UNIQUE (page_path),
    CONSTRAINT ck_report_definition_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE report_export_job (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    report_code VARCHAR(80) NOT NULL,
    request_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    export_format VARCHAR(20) NOT NULL,
    filters_json JSON NOT NULL,
    selected_columns_json JSON NOT NULL,
    watermark_text VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL,
    row_count BIGINT,
    artifact_name VARCHAR(240),
    artifact_mime VARCHAR(120),
    artifact_blob LONGBLOB,
    artifact_checksum CHAR(64),
    error_message VARCHAR(1000),
    requested_by VARCHAR(36) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    started_at DATETIME(3),
    completed_at DATETIME(3),
    CONSTRAINT uk_report_export_request UNIQUE (community_id, request_key),
    CONSTRAINT fk_report_export_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_report_export_definition FOREIGN KEY (report_code) REFERENCES report_definition(report_code),
    CONSTRAINT fk_report_export_requester FOREIGN KEY (requested_by) REFERENCES sys_user(id),
    CONSTRAINT ck_report_export_format CHECK (export_format IN ('CSV', 'XLSX', 'PDF', 'PRINT')),
    CONSTRAINT ck_report_export_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED'))
);
CREATE INDEX idx_report_export_query ON report_export_job (community_id, created_at, status);

CREATE TABLE report_export_event (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    job_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    detail_json JSON NOT NULL,
    detail_checksum CHAR(64) NOT NULL,
    actor_user_id VARCHAR(36) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_report_event_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_report_event_job FOREIGN KEY (job_id) REFERENCES report_export_job(id),
    CONSTRAINT fk_report_event_actor FOREIGN KEY (actor_user_id) REFERENCES sys_user(id),
    CONSTRAINT ck_report_event_type CHECK (event_type IN ('QUEUED', 'STARTED', 'SUCCEEDED', 'FAILED', 'DOWNLOADED'))
);
CREATE INDEX idx_report_event_timeline ON report_export_event (job_id, created_at);

CREATE TABLE receipt_print_job (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    request_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    output_format VARCHAR(20) NOT NULL,
    template_version VARCHAR(40) NOT NULL,
    watermark_text VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL,
    item_count INT NOT NULL,
    artifact_name VARCHAR(240),
    artifact_mime VARCHAR(120),
    artifact_blob LONGBLOB,
    artifact_checksum CHAR(64),
    error_message VARCHAR(1000),
    requested_by VARCHAR(36) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    started_at DATETIME(3),
    completed_at DATETIME(3),
    CONSTRAINT uk_receipt_print_request UNIQUE (community_id, request_key),
    CONSTRAINT fk_receipt_print_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_receipt_print_requester FOREIGN KEY (requested_by) REFERENCES sys_user(id),
    CONSTRAINT ck_receipt_print_format CHECK (output_format IN ('PDF', 'PRINT')),
    CONSTRAINT ck_receipt_print_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_receipt_print_count CHECK (item_count > 0)
);
CREATE INDEX idx_receipt_print_query ON receipt_print_job (community_id, created_at, status);

CREATE TABLE receipt_print_item (
    id VARCHAR(36) PRIMARY KEY,
    job_id VARCHAR(36) NOT NULL,
    receipt_id VARCHAR(36) NOT NULL,
    receipt_no_snapshot VARCHAR(80) NOT NULL,
    receipt_snapshot JSON NOT NULL,
    snapshot_checksum CHAR(64) NOT NULL,
    sequence_no INT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_receipt_print_item UNIQUE (job_id, receipt_id),
    CONSTRAINT uk_receipt_print_sequence UNIQUE (job_id, sequence_no),
    CONSTRAINT fk_receipt_print_item_job FOREIGN KEY (job_id) REFERENCES receipt_print_job(id),
    CONSTRAINT fk_receipt_print_item_receipt FOREIGN KEY (receipt_id) REFERENCES receipt(id)
);

CREATE TABLE notification_batch (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    request_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    batch_no VARCHAR(100) NOT NULL,
    billing_period VARCHAR(20) NOT NULL,
    channel VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    simulated BOOLEAN NOT NULL,
    selected_bill_count INT NOT NULL,
    success_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    content_template VARCHAR(500) NOT NULL,
    requested_by VARCHAR(36) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3),
    CONSTRAINT uk_notification_request UNIQUE (community_id, request_key),
    CONSTRAINT uk_notification_batch_no UNIQUE (batch_no),
    CONSTRAINT fk_notification_batch_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_notification_batch_requester FOREIGN KEY (requested_by) REFERENCES sys_user(id),
    CONSTRAINT ck_notification_channel CHECK (channel IN ('SMS_SIMULATOR', 'WECHAT_SIMULATOR', 'EMAIL_SIMULATOR')),
    CONSTRAINT ck_notification_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED')),
    CONSTRAINT ck_notification_simulated CHECK (simulated = TRUE)
);
CREATE INDEX idx_notification_batch_query ON notification_batch (community_id, billing_period, created_at);

CREATE TABLE notification_message (
    id VARCHAR(36) PRIMARY KEY,
    batch_id VARCHAR(36) NOT NULL,
    bill_id VARCHAR(36) NOT NULL,
    customer_id VARCHAR(36),
    masked_recipient VARCHAR(160) NOT NULL,
    content_snapshot VARCHAR(1000) NOT NULL,
    content_checksum CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    simulated_reference VARCHAR(160) NOT NULL,
    sent_at DATETIME(3),
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_notification_message_bill UNIQUE (batch_id, bill_id),
    CONSTRAINT uk_notification_sim_reference UNIQUE (simulated_reference),
    CONSTRAINT fk_notification_message_batch FOREIGN KEY (batch_id) REFERENCES notification_batch(id),
    CONSTRAINT fk_notification_message_bill FOREIGN KEY (bill_id) REFERENCES bill(id),
    CONSTRAINT fk_notification_message_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
    CONSTRAINT ck_notification_message_status CHECK (status IN ('SENT_SIMULATED', 'FAILED'))
);
CREATE INDEX idx_notification_message_query ON notification_message (batch_id, status, created_at);

ALTER TABLE receipt
    ADD COLUMN print_count INT NOT NULL DEFAULT 0 AFTER event_reason,
    ADD COLUMN last_printed_at DATETIME(3) NULL AFTER print_count,
    ADD CONSTRAINT ck_receipt_print_counter CHECK (print_count >= 0);

-- The 22 rows are the executable catalog. Columns remain an allow-list, never arbitrary SQL.
INSERT INTO report_definition
    (id, report_code, page_path, title, row_grain, formula_note, formula_json,
     columns_json, fixed_sample_json, status, version, created_at, updated_at)
VALUES
('e7000000-0000-0000-0000-000000000001','TRANSACTION_SUMMARY','/reports/transaction-summary','交易汇总','支付渠道日汇总','净额=成功收款-成功冲正；交易日期按 occurred_at。',JSON_OBJECT('netAmount','collectedAmount-refundAmount'),JSON_ARRAY('groupLabel','transactionCount','receivableAmount','collectedAmount','refundAmount','netAmount'),JSON_OBJECT('collectedAmount',100,'refundAmount',20,'netAmount',80),'ACTIVE',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
('e7000000-0000-0000-0000-000000000002','TRANSACTION_DETAILS','/reports/transaction-details','交易明细','一笔不可变支付交易','冲正以 original_transaction_id 关联原交易，金额不覆盖。',JSON_OBJECT('amount','signed immutable transaction amount'),JSON_ARRAY('transactionNo','paidAt','customerName','assetName','paymentChannel','amount','status'),JSON_OBJECT('payment',100,'reversal',-20,'net',80),'ACTIVE',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
('e7000000-0000-0000-0000-000000000003','RECEIPT_BATCH_PRINT','/finance/receipt-batch-print','批量打印收据','一张已签发收据','打印次数只在异步制品成功后增加；每次保留模板与数据快照。',JSON_OBJECT('printCount','successful artifact generations'),JSON_ARRAY('receiptNo','paidAt','assetName','customerName','amount','printCount','status'),JSON_OBJECT('before',1,'printed',2,'after',3),'ACTIVE',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
('e7000000-0000-0000-0000-000000000004','PAYMENTS','/finance/payments','交易记录','一笔支付交易','金额来自不可变交易；已锁定日结的原交易不能直接冲正。',JSON_OBJECT('netAmount','SUM(transaction amount)'),JSON_ARRAY('transactionNo','paymentOrderNo','paidAt','payerName','amount','channel','status'),JSON_OBJECT('payment',100,'reversal',-20,'net',80),'ACTIVE',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
('e7000000-0000-0000-0000-000000000005','ARREARS','/finance/arrears','欠费明细表','一张截至日仍未结清账单','欠费=outstanding_amount；欠费天数=max(0,截至日-due_date)。',JSON_OBJECT('arrearsAmount','outstandingAmount','arrearsDays','MAX(0,asOfDate-dueDate)'),JSON_ARRAY('billId','assetName','customerName','feeName','billingPeriod','receivableAmount','arrearsAmount','arrearsDays'),JSON_OBJECT('receivableAmount',100,'paidAmount',30,'arrearsAmount',70),'ACTIVE',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
('e7000000-0000-0000-0000-000000000006','BILL_NOTIFICATIONS','/finance/bill-notifications','账单通知','一条模拟通知','仅保存脱敏收件人和最小正文快照；当前通道均为模拟器。',JSON_OBJECT('deliveryStatus','persisted simulator result'),JSON_ARRAY('customerName','assetName','billingPeriod','billAmount','channel','deliveryStatus','sentAt'),JSON_OBJECT('selected',3,'sent',3,'failed',0),'ACTIVE',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
('e7000000-0000-0000-0000-000000000007','BILLS','/finance/bills','应收管理','一张应收账单','总额=原金额+调账额；总额=已收+未收。',JSON_OBJECT('totalAmount','originalAmount+adjustmentAmount','outstandingAmount','totalAmount-paidAmount'),JSON_ARRAY('billId','billNo','assetName','customerName','feeName','amount','outstandingAmount','status','version'),JSON_OBJECT('originalAmount',100,'adjustmentAmount',-10,'totalAmount',90,'paidAmount',20,'outstandingAmount',70),'ACTIVE',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
('e7000000-0000-0000-0000-000000000008','COLLECTION_RATE','/reports/collection-rate','收缴率报表','项目/费用项目汇总','收缴率=已收/应收×100；应收为零时为 0。',JSON_OBJECT('collectionRate','collectedAmount/receivableAmount*100'),JSON_ARRAY('scopeName','receivableAmount','collectedAmount','outstandingAmount','collectionRate'),JSON_OBJECT('receivableAmount',100,'collectedAmount',80,'collectionRate',80),'ACTIVE',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
('e7000000-0000-0000-0000-000000000009','ARREARS_CLEARANCE_RATE','/reports/arrears-clearance-rate','清欠率','项目历史欠费汇总','期初欠费=统计开始前账期的期初未收；清欠率=期内清收/期初欠费×100。',JSON_OBJECT('clearanceRate','clearedAmount/openingArrears*100'),JSON_ARRAY('scopeName','openingArrears','clearedAmount','closingArrears','clearanceRate'),JSON_OBJECT('openingArrears',100,'clearedAmount',30,'closingArrears',70,'clearanceRate',30),'ACTIVE',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
('e7000000-0000-0000-0000-000000000010','COMPREHENSIVE_QUERY','/reports/comprehensive-query','综合查询表','预定义账单/交易/调账/通知业务行','各主题仅走固定 SQL 白名单，列必须来自定义列白名单。',JSON_OBJECT('amount','subject-specific immutable amount'),JSON_ARRAY('businessType','businessNo','occurredAt','assetName','customerName','amount','status'),JSON_OBJECT('allowedSubject','BILL','arbitrarySql',false),'ACTIVE',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
('e7000000-0000-0000-0000-000000000011','COLLECTION_CLEARANCE_SUMMARY','/reports/collection-clearance-summary','收清欠汇总表','项目综合汇总','收缴率与清欠率分别使用本期应收、期初历史欠费作为分母。',JSON_OBJECT('collectionRate','currentCollected/currentReceivable*100','clearanceRate','arrearsCleared/historicalArrears*100'),JSON_ARRAY('scopeName','currentReceivable','currentCollected','historicalArrears','arrearsCleared','collectionRate','clearanceRate'),JSON_OBJECT('currentReceivable',100,'currentCollected',80,'historicalArrears',50,'arrearsCleared',10,'collectionRate',80,'clearanceRate',20),'ACTIVE',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
('e7000000-0000-0000-0000-000000000012','CHARGE_DETAILS','/reports/charge-details','收费明细报表','支付分摊到账单费用行','费用实收按 payment_allocation 分摊；同一支付-账单仅一行。',JSON_OBJECT('amount','payment_allocation.allocated_amount'),JSON_ARRAY('paidAt','receiptNo','assetName','customerName','feeName','billingPeriod','amount','cashierName'),JSON_OBJECT('transactionAmount',100,'allocatedAmount',100,'difference',0),'ACTIVE',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
('e7000000-0000-0000-0000-000000000013','DISCOUNT_DETAILS','/reports/discount-details','折扣明细表','一笔已应用折扣/减免调账','折后金额=调整前账单额+负向优惠金额。',JSON_OBJECT('finalAmount','originalAmount-discountAmount'),JSON_ARRAY('discountNo','assetName','customerName','feeName','originalAmount','discountAmount','finalAmount','approvedAt'),JSON_OBJECT('originalAmount',100,'discountAmount',10,'finalAmount',90),'ACTIVE',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
('e7000000-0000-0000-0000-000000000014','PREPAYMENTS','/reports/prepayments','预收款报表','一笔不可变预收分录','增加=max(amount,0)；减少=abs(min(amount,0))；余额取 balance_after。',JSON_OBJECT('creditAmount','MAX(amount,0)','debitAmount','ABS(MIN(amount,0))'),JSON_ARRAY('occurredAt','customerName','assetName','entryType','creditAmount','debitAmount','balance','referenceNo'),JSON_OBJECT('opening',0,'creditAmount',100,'debitAmount',20,'balance',80),'ACTIVE',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
('e7000000-0000-0000-0000-000000000015','OWNERSHIP_TRANSFERS','/reports/ownership-transfers','过户查询','一次所有权转移事件','原/新客户来自事件关联关系，个人姓名始终脱敏。',JSON_OBJECT('effectiveDate','property_relation_event.effective_date'),JSON_ARRAY('transferNo','assetName','previousCustomerName','newCustomerName','effectiveDate','operatorName','status'),JSON_OBJECT('previousCustomerName','张*','newCustomerName','李*'),'ACTIVE',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
('e7000000-0000-0000-0000-000000000016','REMINDERS','/reports/reminders','提醒明细','一条模拟提醒消息','提醒正文按最小必要原则保存并以 SHA-256 校验。',JSON_OBJECT('deliveryStatus','notification_message.status'),JSON_ARRAY('reminderNo','reminderType','customerName','assetName','channel','deliveryStatus','sentAt'),JSON_OBJECT('contentChecksumLength',64,'simulated',true),'ACTIVE',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
('e7000000-0000-0000-0000-000000000017','FEE_STATUS','/reports/fee-status','费用情况表','费用项目汇总','户数按资产-费用项目去重；金额直接汇总账单。',JSON_OBJECT('receivableCount','COUNT(DISTINCT asset,fee)','collectedCount','COUNT(DISTINCT paid asset,fee)'),JSON_ARRAY('feeName','receivableCount','receivableAmount','collectedCount','collectedAmount','outstandingAmount'),JSON_OBJECT('receivableCount',10,'collectedCount',8),'ACTIVE',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
('e7000000-0000-0000-0000-000000000018','INVOICE_STATISTICS','/reports/invoice-statistics','发票统计表','模拟发票操作类型汇总','开具/作废/红冲分别按 operation_type 汇总；当前结果不代表税控生产口径。',JSON_OBJECT('issuedAmount','SUM ISSUE','redAmount','SUM RED'),JSON_ARRAY('groupLabel','issuedCount','issuedAmount','voidedCount','voidedAmount','redCount','redAmount'),JSON_OBJECT('issuedCount',2,'redCount',1),'ACTIVE',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
('e7000000-0000-0000-0000-000000000019','DEPOSITS','/finance/deposits','押金记录表','一个押金账户','收取/退还来自不可变押金分录；账户余额等于分录累计。',JSON_OBJECT('balance','receivedAmount-refundedAmount'),JSON_ARRAY('depositNo','customerName','assetName','depositType','receivedAmount','refundedAmount','balance','status'),JSON_OBJECT('receivedAmount',100,'refundedAmount',20,'balance',80),'ACTIVE',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
('e7000000-0000-0000-0000-000000000020','DAILY_SETTLEMENT_DETAILS','/reports/daily-settlement-details','日结明细表','日结单-支付渠道汇总','净额=成功收款+冲正；所属交易与日结快照可追溯。',JSON_OBJECT('netAmount','collectedAmount-refundedAmount'),JSON_ARRAY('settlementNo','cashierName','paymentChannel','transactionCount','collectedAmount','refundedAmount','netAmount'),JSON_OBJECT('collectedAmount',100,'refundedAmount',20,'netAmount',80),'ACTIVE',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
('e7000000-0000-0000-0000-000000000021','ADJUSTMENTS','/finance/adjustments','调账记录','一笔调账审批记录','调整后=调整前+变更额；原账单金额不原地覆盖。',JSON_OBJECT('afterAmount','beforeAmount+changeAmount'),JSON_ARRAY('adjustmentNo','billNo','adjustmentType','beforeAmount','changeAmount','afterAmount','status','createdAt'),JSON_OBJECT('beforeAmount',100,'changeAmount',-10,'afterAmount',90),'ACTIVE',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
('e7000000-0000-0000-0000-000000000022','BANK_TRUST','/finance/bank-trust','银行信托','模拟托收批次汇总','未获银行协议前只返回显式 simulator 读模型，不伪装生产托收。',JSON_OBJECT('successAmount','SUM simulated successes'),JSON_ARRAY('trustNo','bankChannel','submittedCount','submittedAmount','successCount','successAmount','reconcileStatus'),JSON_OBJECT('adapter','BANK_TRUST_SIMULATOR','productionConnected',false),'ACTIVE',1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3));
