package com.aerotrace.recovery.model.dto.response;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/** One page of a list, with the numbers a client needs to ask for the next one. */
public record PageResponse<T>(
    List<T> items, int page, int size, long totalElements, int totalPages) {

  public static <S, T> PageResponse<T> of(Page<S> page, Function<S, T> mapper) {
    return new PageResponse<>(
        page.getContent().stream().map(mapper).toList(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements(),
        page.getTotalPages());
  }
}
