-- Report net cash movements without rewriting the already-applied V21 metadata.
-- Reversals and refunds are immutable negative allocations, so closing arrears
-- and charge details must retain their signed effect.

UPDATE report_definition
SET formula_note='期初欠费=统计开始前的调账后应收减去此前净收款；期内清欠额和清欠率不低于零且不超过期初欠费；期末欠费按期间带符号净分配计算，冲正或退款会使期末欠费回升。',
    formula_json=JSON_OBJECT(
        'openingArrears','max(bill.totalAmount-netAllocationBeforePeriod,0)',
        'clearedAmount','min(max(signedPeriodAllocation,0),openingArrears)',
        'closingArrears','max(openingArrears-signedPeriodAllocation,0)',
        'clearanceRate','clearedAmount/openingArrears*100'),
    version=version+1,
    updated_at=CURRENT_TIMESTAMP(3)
WHERE report_code='ARREARS_CLEARANCE_RATE';

UPDATE report_definition
SET formula_note='本期应收按调账后账单金额及费用行占比分摊；本期实收按成功支付、冲正和退款的带符号净分配归集；历史清欠额不低于零且不超过期初欠费，作废账单不计入。',
    formula_json=JSON_OBJECT(
        'feeShare','selectedItemAmount/allItemAmount',
        'currentReceivable','bill.totalAmount*feeShare',
        'currentCollected','signedPeriodAllocation*feeShare',
        'historicalArrears','max(bill.totalAmount-netAllocationBeforePeriod,0)*feeShare',
        'arrearsCleared','min(max(signedPeriodAllocation,0),openingArrears)*feeShare',
        'collectionRate','currentCollected/currentReceivable*100',
        'clearanceRate','arrearsCleared/historicalArrears*100'),
    version=version+1,
    updated_at=CURRENT_TIMESTAMP(3)
WHERE report_code='COLLECTION_CLEARANCE_SUMMARY';

UPDATE report_definition
SET formula_note='一行对应一次成功支付、冲正或退款到账单的不可变分摊；冲正和退款以负数展示；筛选费用项目时，金额按费用行原始金额占账单全部费用行原始金额的比例分配；换票仅展示当前已签发或最新收据。',
    formula_json=JSON_OBJECT(
        'feeShare','selectedItemAmount/allItemAmount',
        'amount','signedPaymentAllocation.allocatedAmount*feeShare',
        'receipt','oneCurrentOrLatestReceiptPerPaymentOrder'),
    version=version+1,
    updated_at=CURRENT_TIMESTAMP(3)
WHERE report_code='CHARGE_DETAILS';
