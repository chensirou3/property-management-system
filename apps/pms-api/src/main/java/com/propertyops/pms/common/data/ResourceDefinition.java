package com.propertyops.pms.common.data;

import java.util.Map;
import java.util.Set;

public record ResourceDefinition(
        String apiName,
        String tableName,
        String selectSql,
        String fromSql,
        String scopeExpression,
        String readPermission,
        String writePermission,
        Set<String> searchColumns,
        String statusExpression,
        String categoryExpression,
        Map<String, String> sortColumns,
        String defaultSort,
        Set<String> createColumns,
        Set<String> updateColumns,
        String archiveColumn,
        boolean communityResource
) {
    public boolean writable() {
        return writePermission != null && !createColumns.isEmpty();
    }
}
