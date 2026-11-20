package com.aerotrace.common.export;

import java.util.List;

/**
 * One page of an internal export ({@link ExportApi}), carried as {@code data} of the shared {@code
 * ApiResponse}.
 *
 * @param items the records, in ascending key order
 * @param nextAfter the key to ask the next page after (the last key of this page); null when this
 *     was the last page
 */
public record ExportPage<T>(List<T> items, String nextAfter) {}
