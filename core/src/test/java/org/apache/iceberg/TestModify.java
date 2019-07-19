/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg;

import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.types.Types;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static com.google.common.collect.ImmutableSet.of;
import static java.util.Collections.emptySet;
import static org.apache.iceberg.types.Types.NestedField.optional;
import static org.apache.iceberg.types.Types.NestedField.required;

public class TestModify extends TableTestBase {

  private static final String TABLE_NAME = "modify_table";

  private static final Schema DATE_SCHEMA = new Schema(
      required(1, "id", Types.LongType.get()),
      optional(2, "data", Types.StringType.get()),
      required(3, "date", Types.StringType.get()));

  private static final PartitionSpec PARTITION_BY_DATE = PartitionSpec
      .builderFor(DATE_SCHEMA)
      .identity("date")
      .build();

  private static final DataFile FILE_DAY_1 = DataFiles
      .builder(PARTITION_BY_DATE)
      .withPath("/path/to/data-1.parquet")
      .withFileSizeInBytes(0)
      .withPartitionPath("date=2018-06-08")
      .withMetrics(new Metrics(5L,
          null, // no column sizes
          ImmutableMap.of(1, 5L, 2, 3L), // value count
          ImmutableMap.of(1, 0L, 2, 2L), // null count
          ImmutableMap.of(1, longToBuffer(0L)), // lower bounds
          ImmutableMap.of(1, longToBuffer(4L)) // upper bounds
      ))
      .build();

  private static final DataFile FILE_DAY_2 = DataFiles
      .builder(PARTITION_BY_DATE)
      .withPath("/path/to/data-2.parquet")
      .withFileSizeInBytes(0)
      .withPartitionPath("date=2018-06-09")
      .withMetrics(new Metrics(5L,
          null, // no column sizes
          ImmutableMap.of(1, 5L, 2, 3L), // value count
          ImmutableMap.of(1, 0L, 2, 2L), // null count
          ImmutableMap.of(1, longToBuffer(5L)), // lower bounds
          ImmutableMap.of(1, longToBuffer(9L)) // upper bounds
      ))
      .build();

  private static final DataFile FILE_DAY_2_MODIFIED = DataFiles
      .builder(PARTITION_BY_DATE)
      .withPath("/path/to/data-3.parquet")
      .withFileSizeInBytes(0)
      .withPartitionPath("date=2018-06-09")
      .withMetrics(new Metrics(5L,
          null, // no column sizes
          ImmutableMap.of(1, 5L, 2, 3L), // value count
          ImmutableMap.of(1, 0L, 2, 2L), // null count
          ImmutableMap.of(1, longToBuffer(5L)), // lower bounds
          ImmutableMap.of(1, longToBuffer(9L)) // upper bounds
      ))
      .build();

  private static final Expression EXPRESSION_DAY_2 = Expressions.equal("date", "2018-06-09");

  private static final Expression EXPRESSION_DAY_2_ID_RANGE = Expressions.and(
      Expressions.greaterThanOrEqual("id", 5L),
      Expressions.lessThanOrEqual("id", 9L));

  private static ByteBuffer longToBuffer(long value) {
    return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0, value);
  }

  private Table table = null;

  @Before
  public void before() throws IOException {
    File tableDir = temp.newFolder();
    Assert.assertTrue(tableDir.delete());

    this.table = TestTables.create(tableDir, TABLE_NAME, DATE_SCHEMA, PARTITION_BY_DATE);
  }

  @Test
  public void testModifyOnEmptyTableNotValidated() {
    // Given:

    Assert.assertNull("Should be empty table", table.currentSnapshot());

    // When:

    table.newModify()
        .modifyFiles(emptySet(), of(FILE_DAY_2_MODIFIED))
        .commit();

    // Then:

    Assert.assertNotNull("Should create a new snapshot", table.currentSnapshot());
  }

  @Test
  public void testModifyOnEmptyTableStrictValidated() {
    // Given:

    Assert.assertNull("Should be empty table", table.currentSnapshot());
    Long baseSnapshotId = null;

    // When:

    table.newModify()
        .modifyFiles(emptySet(), of(FILE_DAY_2_MODIFIED))
        .validate(baseSnapshotId)
        .commit();

    // Then:

    Assert.assertNotNull("Should create a new snapshot", table.currentSnapshot());
  }

  @Test
  public void testModifyOnEmptyTableTimelineValidated() {
    // Given:

    Assert.assertNull("Should be empty table", table.currentSnapshot());
    Long baseSnapshotId = null;

    // When:

    table.newModify()
        .modifyFiles(emptySet(), of(FILE_DAY_2_MODIFIED))
        .validate(baseSnapshotId, EXPRESSION_DAY_2)
        .commit();

    // Then:

    Assert.assertNotNull("Should create a new snapshot", table.currentSnapshot());
  }

  @Test
  public void testModifyTableNotValidated() {
    // Given:

    table.newAppend()
        .appendFile(FILE_DAY_1)
        .appendFile(FILE_DAY_2)
        .commit();

    Assert.assertNotNull("Should not be empty table", table.currentSnapshot());
    long baseSnapshotId = table.currentSnapshot().snapshotId();

    // When:

    table.newModify()
        .modifyFiles(of(FILE_DAY_2), of(FILE_DAY_2_MODIFIED))
        .commit();

    // Then:

    Assert.assertNotEquals("Should create a new snapshot",
        baseSnapshotId, table.currentSnapshot().snapshotId());
  }

  @Test
  public void testModifyTableStrictValidated() {
    // Given:

    table.newAppend()
        .appendFile(FILE_DAY_1)
        .appendFile(FILE_DAY_2)
        .commit();

    Assert.assertNotNull("Should not be empty table", table.currentSnapshot());
    long baseSnapshotId = table.currentSnapshot().snapshotId();

    // When:

    table.newModify()
        .modifyFiles(of(FILE_DAY_2), of(FILE_DAY_2_MODIFIED))
        .validate(baseSnapshotId)
        .commit();

    // Then:

    Assert.assertNotEquals("Should create a new snapshot",
        baseSnapshotId, table.currentSnapshot().snapshotId());
  }

  @Test
  public void testModifyTableTimelineValidated() {
    // Given:

    table.newAppend()
        .appendFile(FILE_DAY_1)
        .appendFile(FILE_DAY_2)
        .commit();

    Assert.assertNotNull("Should not be empty table", table.currentSnapshot());
    long baseSnapshotId = table.currentSnapshot().snapshotId();

    // When:

    table.newModify()
        .modifyFiles(of(FILE_DAY_2), of(FILE_DAY_2_MODIFIED))
        .validate(baseSnapshotId, EXPRESSION_DAY_2)
        .commit();

    // Then:

    Assert.assertNotEquals("Should create a new snapshot",
        baseSnapshotId, table.currentSnapshot().snapshotId());
  }

  @Test
  public void testModifyCompatibleTableNotValidated() {
    // Given:

    table.newAppend()
        .appendFile(FILE_DAY_2)
        .commit();

    Assert.assertNotNull("Should not be empty table", table.currentSnapshot());

    // When:

    ModifyFiles modify = table.newModify()
        .modifyFiles(of(FILE_DAY_2), of(FILE_DAY_2_MODIFIED));

    table.newAppend()
        .appendFile(FILE_DAY_1)
        .commit();
    long timelineSnapshotId = table.currentSnapshot().snapshotId();

    modify.commit();

    // Then:

    Assert.assertNotEquals("Should create a new snapshot",
        timelineSnapshotId, table.currentSnapshot().snapshotId());
  }

  @Test
  public void testModifyCompatibleTableStrictValidated() {
    // Given:

    table.newAppend()
        .appendFile(FILE_DAY_2)
        .commit();

    Assert.assertNotNull("Should not be empty table", table.currentSnapshot());
    long baseSnapshotId = table.currentSnapshot().snapshotId();

    // When:

    ModifyFiles modify = table.newModify()
        .modifyFiles(of(FILE_DAY_2), of(FILE_DAY_2_MODIFIED))
        .validate(baseSnapshotId);

    table.newAppend()
        .appendFile(FILE_DAY_1)
        .commit();
    long timelineSnapshotId = table.currentSnapshot().snapshotId();

    // Then:

    AssertHelpers.assertThrows("Should reject commit",
        ValidationException.class, "Modify operation requires no changes to timeline.",
        modify::commit);

    Assert.assertEquals("Should not create a new snapshot",
        timelineSnapshotId, table.currentSnapshot().snapshotId());
  }

  @Test
  public void testModifyCompatibleAdditionTableTimelineValidated() {
    // Given:

    table.newAppend()
        .appendFile(FILE_DAY_2)
        .commit();

    Assert.assertNotNull("Should not be empty table", table.currentSnapshot());
    long baseSnapshotId = table.currentSnapshot().snapshotId();

    // When:

    ModifyFiles modify = table.newModify()
        .modifyFiles(of(FILE_DAY_2), of(FILE_DAY_2_MODIFIED))
        .validate(baseSnapshotId, EXPRESSION_DAY_2);

    table.newAppend()
        .appendFile(FILE_DAY_1)
        .commit();
    long timelineSnapshotId = table.currentSnapshot().snapshotId();

    modify.commit();

    // Then:

    Assert.assertNotEquals("Should create a new snapshot",
        timelineSnapshotId, table.currentSnapshot().snapshotId());
  }

  @Test
  public void testModifyCompatibleDeletionTableTimelineValidated() {
    // Given:

    table.newAppend()
        .appendFile(FILE_DAY_1)
        .appendFile(FILE_DAY_2)
        .commit();

    Assert.assertNotNull("Should not be empty table", table.currentSnapshot());
    long baseSnapshotId = table.currentSnapshot().snapshotId();

    // When:

    ModifyFiles modify = table.newModify()
        .modifyFiles(of(FILE_DAY_2), of(FILE_DAY_2_MODIFIED))
        .validate(baseSnapshotId, EXPRESSION_DAY_2);

    table.newDelete()
        .deleteFile(FILE_DAY_1)
        .commit();
    long timelineSnapshotId = table.currentSnapshot().snapshotId();

    modify.commit();

    // Then:

    Assert.assertNotEquals("Should create a new snapshot",
        timelineSnapshotId, table.currentSnapshot().snapshotId());
  }

  @Test
  public void testModifyIncompatibleAdditionTableTimelineValidated() {
    // Given:

    table.newAppend()
        .appendFile(FILE_DAY_1)
        .commit();

    Assert.assertNotNull("Should not be empty table", table.currentSnapshot());
    long baseSnapshotId = table.currentSnapshot().snapshotId();

    // When:

    ModifyFiles modify = table.newModify()
        .modifyFiles(emptySet(), of(FILE_DAY_2_MODIFIED))
        .validate(baseSnapshotId, EXPRESSION_DAY_2);

    table.newAppend()
        .appendFile(FILE_DAY_2)
        .commit();
    long timelineSnapshotId = table.currentSnapshot().snapshotId();

    // Then:

    AssertHelpers.assertThrows("Should reject commit",
        ValidationException.class, "Modify operation requires no in-range changes to timeline.",
        modify::commit);

    Assert.assertEquals("Should not create a new snapshot",
        timelineSnapshotId, table.currentSnapshot().snapshotId());
  }

  @Test
  public void testModifyIncompatibleDeletionTableTimelineValidated() {
    // Given:

    table.newAppend()
        .appendFile(FILE_DAY_1)
        .appendFile(FILE_DAY_2)
        .commit();

    Assert.assertNotNull("Should not be empty table", table.currentSnapshot());
    long baseSnapshotId = table.currentSnapshot().snapshotId();

    // When:

    ModifyFiles modify = table.newModify()
        .modifyFiles(of(FILE_DAY_2), of(FILE_DAY_2_MODIFIED))
        .validate(baseSnapshotId, EXPRESSION_DAY_2);

    table.newDelete()
        .deleteFile(FILE_DAY_2)
        .commit();
    long timelineSnapshotId = table.currentSnapshot().snapshotId();

    // Then:

    AssertHelpers.assertThrows("Should reject commit",
        ValidationException.class, "Missing required files to delete:",
        modify::commit);

    Assert.assertEquals("Should not create a new snapshot",
        timelineSnapshotId, table.currentSnapshot().snapshotId());
  }

  @Test
  public void testModifyIncompatibleRewriteTableTimelineValidated() {
    // Given:

    table.newAppend()
        .appendFile(FILE_DAY_1)
        .appendFile(FILE_DAY_2)
        .commit();

    Assert.assertNotNull("Should not be empty table", table.currentSnapshot());
    long baseSnapshotId = table.currentSnapshot().snapshotId();

    // When:

    ModifyFiles modify = table.newModify()
        .modifyFiles(of(FILE_DAY_2), of(FILE_DAY_2_MODIFIED))
        .validate(baseSnapshotId, EXPRESSION_DAY_2);

    table.newRewrite()
        .rewriteFiles(of(FILE_DAY_2), of(FILE_DAY_2))
        .commit();
    long timelineSnapshotId = table.currentSnapshot().snapshotId();

    // Then:

    AssertHelpers.assertThrows("Should reject commit",
        ValidationException.class, "Modify operation requires no in-range changes to timeline.",
        modify::commit);

    Assert.assertEquals("Should not create a new snapshot",
        timelineSnapshotId, table.currentSnapshot().snapshotId());
  }

  @Test
  public void testModifyCompatibleExpirationAdditionTableTimelineValidated() {
    // Given:

    table.newAppend()
        .appendFile(FILE_DAY_2)
        .commit(); // id 1

    Assert.assertNotNull("Should not be empty table", table.currentSnapshot());
    long baseSnapshotId = table.currentSnapshot().snapshotId();

    // When:

    ModifyFiles modify = table.newModify()
        .modifyFiles(of(FILE_DAY_2), of(FILE_DAY_2_MODIFIED))
        .validate(baseSnapshotId, EXPRESSION_DAY_2);

    table.newAppend()
        .appendFile(FILE_DAY_1)
        .commit(); // id 2

    table.expireSnapshots()
        .expireSnapshotId(1L)
        .commit();
    long timelineSnapshotId = table.currentSnapshot().snapshotId();

    modify.commit();

    // Then:

    Assert.assertNotEquals("Should create a new snapshot",
        timelineSnapshotId, table.currentSnapshot().snapshotId());
  }

  @Test
  public void testModifyCompatibleExpirationDeletionTableTimelineValidated() {
    // Given:

    table.newAppend()
        .appendFile(FILE_DAY_1)
        .appendFile(FILE_DAY_2)
        .commit(); // id 1

    Assert.assertNotNull("Should not be empty table", table.currentSnapshot());
    long baseSnapshotId = table.currentSnapshot().snapshotId();

    // When:

    ModifyFiles modify = table.newModify()
        .modifyFiles(of(FILE_DAY_2), of(FILE_DAY_2_MODIFIED))
        .validate(baseSnapshotId, EXPRESSION_DAY_2);

    table.newDelete()
        .deleteFile(FILE_DAY_1)
        .commit(); // id 2

    table.expireSnapshots()
        .expireSnapshotId(1L)
        .commit();
    long timelineSnapshotId = table.currentSnapshot().snapshotId();

    modify.commit();

    // Then:

    Assert.assertNotEquals("Should create a new snapshot",
        timelineSnapshotId, table.currentSnapshot().snapshotId());
  }

  @Test
  public void testModifyIncompatibleExpirationTableTimelineValidated() {
    // Given:

    table.newAppend()
        .appendFile(FILE_DAY_1)
        .commit(); // id 1

    Assert.assertNotNull("Should not be empty table", table.currentSnapshot());
    long baseSnapshotId = table.currentSnapshot().snapshotId();

    // When:

    ModifyFiles modify = table.newModify()
        .modifyFiles(emptySet(), of(FILE_DAY_2_MODIFIED))
        .validate(baseSnapshotId, EXPRESSION_DAY_2);

    table.newAppend()
        .appendFile(FILE_DAY_2)
        .commit(); // id 2

    table.newDelete()
        .deleteFile(FILE_DAY_1)
        .commit(); // id 3

    table.expireSnapshots()
        .expireSnapshotId(2L)
        .commit();
    long timelineSnapshotId = table.currentSnapshot().snapshotId();

    // Then:

    AssertHelpers.assertThrows("Should reject commit",
        ValidationException.class, "Modify operation requires to timeline to be present.",
        modify::commit);

    Assert.assertEquals("Should not create a new snapshot",
        timelineSnapshotId, table.currentSnapshot().snapshotId());
  }

  @Test
  public void testModifyIncompatibleBaseExpirationEmptyTableTimelineValidated() {
    // Given:

    Assert.assertNull("Should be empty table", table.currentSnapshot());
    Long baseSnapshotId = null;

    // When:

    ModifyFiles modify = table.newModify()
        .modifyFiles(emptySet(), of(FILE_DAY_2_MODIFIED))
        .validate(baseSnapshotId, EXPRESSION_DAY_2);

    table.newAppend()
        .appendFile(FILE_DAY_2)
        .commit(); // id 1

    table.newDelete()
        .deleteFile(FILE_DAY_1)
        .commit(); // id 2

    table.expireSnapshots()
        .expireSnapshotId(1L)
        .commit();
    long timelineSnapshotId = table.currentSnapshot().snapshotId();

    // Then:

    AssertHelpers.assertThrows("Should reject commit",
        ValidationException.class, "Modify operation requires to timeline to be present.",
        modify::commit);

    Assert.assertEquals("Should not create a new snapshot",
        timelineSnapshotId, table.currentSnapshot().snapshotId());
  }

  @Test
  public void testModifyergEmptyTableTimelineValidated() {
    // Given:

    Assert.assertNull("Should be empty table", table.currentSnapshot());
    Long baseSnapshotId = null;

    // When:

    ModifyFiles modify = table.newModify()
        .modifyFiles(emptySet(), of(FILE_DAY_2_MODIFIED))
        .validate(baseSnapshotId, EXPRESSION_DAY_2_ID_RANGE);

    table.newAppend()
        .appendFile(FILE_DAY_1)
        .commit(); // id 1
    long timelineSnapshotId = table.currentSnapshot().snapshotId();

    // Then:

    AssertHelpers.assertThrows("Should reject commit",
        ValidationException.class, "Modify operation requires no in-range changes to timeline.",
        modify::commit);

    Assert.assertEquals("Should not create a new snapshot",
        timelineSnapshotId, table.currentSnapshot().snapshotId());
  }
}