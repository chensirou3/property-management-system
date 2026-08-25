package com.propertyops.pms.common.data;

import java.util.List;

public record PageResponse<T>(List<T> items, int page, int size, long total) {
}
