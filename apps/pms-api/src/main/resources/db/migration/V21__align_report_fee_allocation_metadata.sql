-- Keep report evidence aligned with the executable ledger queries.
-- Adjustments live at bill level, so every affected report allocates the adjusted
-- bill/payment amount by each fee line's share of the bill's original item total.

UPDATE report_definition
SET formula_note='调账后应收、已收和欠费均按费用行原始金额占账单全部费用行原始金额的比例分摊；作废账单不计入。收缴率=已收/应收×100，应收为零时为0。',
    formula_json=JSON_OBJECT(
        'feeShare','selectedItemAmount/allItemAmount',
        'receivableAmount','bill.totalAmount*feeShare',
        'collectedAmount','bill.paidAmount*feeShare',
        'outstandingAmount','bill.outstandingAmount*feeShare',
        'collectionRate','collectedAmount/receivableAmount*100'),
    version=version+1,
    updated_at=CURRENT_TIMESTAMP(3)
WHERE report_code='COLLECTION_RATE';

UPDATE report_definition
SET formula_note='本期应收按调账后账单金额及费用行占比分摊；实收和清欠按统计期间成功支付分摊的发生时间归集；历史清欠不超过期初欠费，作废账单不计入。',
    formula_json=JSON_OBJECT(
        'feeShare','selectedItemAmount/allItemAmount',
        'currentReceivable','bill.totalAmount*feeShare',
        'currentCollected','successfulPeriodPaymentAllocation*feeShare',
        'historicalArrears','max(bill.totalAmount-paidBeforePeriod,0)*feeShare',
        'arrearsCleared','min(max(periodPaymentAllocation,0),openingArrears)*feeShare',
        'collectionRate','currentCollected/currentReceivable*100',
        'clearanceRate','arrearsCleared/historicalArrears*100'),
    version=version+1,
    updated_at=CURRENT_TIMESTAMP(3)
WHERE report_code='COLLECTION_CLEARANCE_SUMMARY';

UPDATE report_definition
SET formula_note='一行对应一次成功支付到账单的分摊；筛选费用项目时，金额按费用行原始金额占账单全部费用行原始金额的比例分配；换票仅展示当前已签发或最新收据。',
    formula_json=JSON_OBJECT(
        'feeShare','selectedItemAmount/allItemAmount',
        'amount','paymentAllocation.allocatedAmount*feeShare',
        'receipt','oneCurrentOrLatestReceiptPerPaymentOrder'),
    version=version+1,
    updated_at=CURRENT_TIMESTAMP(3)
WHERE report_code='CHARGE_DETAILS';

UPDATE report_definition
SET formula_note='户数按资产和费用项目去重；调账后应收、已收和欠费均按费用行原始金额占账单全部费用行原始金额的比例分摊；作废账单不计入。',
    formula_json=JSON_OBJECT(
        'feeShare','selectedItemAmount/allItemAmount',
        'receivableAmount','bill.totalAmount*feeShare',
        'collectedAmount','bill.paidAmount*feeShare',
        'outstandingAmount','bill.outstandingAmount*feeShare',
        'receivableCount','COUNT(DISTINCT asset,fee)',
        'collectedCount','COUNT(DISTINCT paidAsset,fee)'),
    version=version+1,
    updated_at=CURRENT_TIMESTAMP(3)
WHERE report_code='FEE_STATUS';
