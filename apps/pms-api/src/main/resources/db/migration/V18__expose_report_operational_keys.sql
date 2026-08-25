-- Report rows may expose governed, non-rendered identifiers needed by an authorized
-- operation. The UI column catalog remains the visible-column authority.
UPDATE report_definition
SET columns_json = JSON_ARRAY(
        'receiptId', 'receiptNo', 'paidAt', 'assetName', 'customerName',
        'amount', 'printCount', 'status'
    ),
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP(3)
WHERE report_code = 'RECEIPT_BATCH_PRINT'
  AND JSON_CONTAINS(columns_json, JSON_QUOTE('receiptId')) = 0;
