package com.aerotrace.search.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.search.indexer.ReadModelWriter.ItemResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure rules of the writer: merging the results of one input, and reading a 409. */
class ItemResultTest {

  private static final ItemResult INDEXED = new ItemResult(Outcome.INDEXED, null);
  private static final ItemResult STALE = new ItemResult(Outcome.STALE, null);

  @Test
  @DisplayName("an input is refused when any of its writes was; otherwise the primary outcome")
  void merge() {
    ItemResult refused = ItemResult.rejected("mapper_parsing_exception");

    assertThat(ItemResult.merge(INDEXED, null)).isEqualTo(INDEXED);
    assertThat(ItemResult.merge(STALE, INDEXED)).isEqualTo(STALE);
    assertThat(ItemResult.merge(INDEXED, refused)).isEqualTo(refused);
    assertThat(ItemResult.merge(refused, ItemResult.rejected("other"))).isEqualTo(refused);
    assertThat(ItemResult.merge(List.of(INDEXED, STALE), List.of(refused)))
        .containsExactly(refused, STALE);
  }

  @Test
  @DisplayName(
      "a 409 with equal versions is a duplicate; with a higher current version, or a reason"
          + " that cannot be read, it is stale")
  void conflictOutcome() {
    String prefix = "[case-1]: version conflict, ";
    assertThat(
            ReadModelWriter.conflictOutcome(
                prefix + "current version [7] is higher or equal to the one provided [7]"))
        .isEqualTo(Outcome.DUPLICATE);
    assertThat(
            ReadModelWriter.conflictOutcome(
                prefix + "current version [8] is higher or equal to the one provided [7]"))
        .isEqualTo(Outcome.STALE);
    assertThat(ReadModelWriter.conflictOutcome("version conflict")).isEqualTo(Outcome.STALE);
    assertThat(ReadModelWriter.conflictOutcome(null)).isEqualTo(Outcome.STALE);
  }
}
