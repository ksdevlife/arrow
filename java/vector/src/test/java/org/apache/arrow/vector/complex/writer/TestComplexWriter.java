/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.arrow.vector.complex.writer;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.netty.buffer.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.SchemaChangeCallBack;
import org.apache.arrow.vector.NullableFloat8Vector;
import org.apache.arrow.vector.NullableFloat4Vector;
import org.apache.arrow.vector.NullableBigIntVector;
import org.apache.arrow.vector.NullableIntVector;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.complex.MapVector;
import org.apache.arrow.vector.complex.NullableMapVector;
import org.apache.arrow.vector.complex.UnionVector;
import org.apache.arrow.vector.complex.impl.ComplexWriterImpl;
import org.apache.arrow.vector.complex.impl.SingleMapReaderImpl;
import org.apache.arrow.vector.complex.impl.UnionListReader;
import org.apache.arrow.vector.complex.impl.UnionListWriter;
import org.apache.arrow.vector.complex.impl.UnionReader;
import org.apache.arrow.vector.complex.impl.UnionWriter;
import org.apache.arrow.vector.complex.impl.SingleMapWriter;
import org.apache.arrow.vector.complex.reader.IntReader;
import org.apache.arrow.vector.complex.reader.Float8Reader;
import org.apache.arrow.vector.complex.reader.Float4Reader;
import org.apache.arrow.vector.complex.reader.BigIntReader;
import org.apache.arrow.vector.complex.reader.BaseReader.MapReader;
import org.apache.arrow.vector.complex.reader.FieldReader;
import org.apache.arrow.vector.complex.writer.BaseWriter.ComplexWriter;
import org.apache.arrow.vector.complex.writer.BaseWriter.ListWriter;
import org.apache.arrow.vector.complex.writer.BaseWriter.MapWriter;
import org.apache.arrow.vector.holders.IntHolder;
import org.apache.arrow.vector.holders.NullableTimeStampNanoTZHolder;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.ArrowType.ArrowTypeID;
import org.apache.arrow.vector.types.pojo.ArrowType.Int;
import org.apache.arrow.vector.types.pojo.ArrowType.Struct;
import org.apache.arrow.vector.types.pojo.ArrowType.Timestamp;
import org.apache.arrow.vector.types.pojo.ArrowType.Union;
import org.apache.arrow.vector.types.pojo.ArrowType.Utf8;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.util.CallBack;
import org.apache.arrow.vector.util.DateUtility;
import org.apache.arrow.vector.util.JsonStringArrayList;
import org.apache.arrow.vector.util.JsonStringHashMap;
import org.apache.arrow.vector.util.Text;
import org.apache.arrow.vector.util.TransferPair;
import org.joda.time.LocalDateTime;
import org.junit.Assert;
import org.junit.Test;

public class TestComplexWriter {

  private static final BufferAllocator allocator = new RootAllocator(Integer.MAX_VALUE);

  private static final int COUNT = 100;

  @Test
  public void simpleNestedTypes() {
    MapVector parent = populateMapVector(null);
    MapReader rootReader = new SingleMapReaderImpl(parent).reader("root");
    for (int i = 0; i < COUNT; i++) {
      rootReader.setPosition(i);
      Assert.assertEquals(i, rootReader.reader("int").readInteger().intValue());
      Assert.assertEquals(i, rootReader.reader("bigInt").readLong().longValue());
    }

    parent.close();
  }

  @Test
  public void transferPairSchemaChange() {
    SchemaChangeCallBack callBack1 = new SchemaChangeCallBack();
    SchemaChangeCallBack callBack2 = new SchemaChangeCallBack();
    MapVector parent = populateMapVector(callBack1);

    TransferPair tp = parent.getTransferPair("newVector", allocator, callBack2);

    ComplexWriter writer = new ComplexWriterImpl("newWriter", parent);
    MapWriter rootWriter = writer.rootAsMap();
    IntWriter intWriter = rootWriter.integer("newInt");
    intWriter.writeInt(1);
    writer.setValueCount(1);

    assertTrue(callBack1.getSchemaChangedAndReset());
    // The second vector should not have registered a schema change
    assertFalse(callBack1.getSchemaChangedAndReset());
  }

  private MapVector populateMapVector(CallBack callBack) {
    MapVector parent = new MapVector("parent", allocator, new FieldType(false, Struct.INSTANCE, null, null), callBack);
    ComplexWriter writer = new ComplexWriterImpl("root", parent);
    MapWriter rootWriter = writer.rootAsMap();
    IntWriter intWriter = rootWriter.integer("int");
    BigIntWriter bigIntWriter = rootWriter.bigInt("bigInt");
    for (int i = 0; i < COUNT; i++) {
      rootWriter.start();
      intWriter.writeInt(i);
      bigIntWriter.writeBigInt(i);
      rootWriter.end();
    }
    writer.setValueCount(COUNT);
    return parent;
  }

  @Test
  public void nullableMap() {
    try (MapVector mapVector = MapVector.empty("parent", allocator)) {
      ComplexWriter writer = new ComplexWriterImpl("root", mapVector);
      MapWriter rootWriter = writer.rootAsMap();
      for (int i = 0; i < COUNT; i++) {
        rootWriter.start();
        if (i % 2 == 0) {
          MapWriter mapWriter = rootWriter.map("map");
          mapWriter.setPosition(i);
          mapWriter.start();
          mapWriter.bigInt("nested").writeBigInt(i);
          mapWriter.end();
        }
        rootWriter.end();
      }
      writer.setValueCount(COUNT);
      checkNullableMap(mapVector);
    }
  }

  /**
   * This test is similar to {@link #nullableMap()} ()} but we get the inner map writer once at the beginning
   */
  @Test
  public void nullableMap2() {
    try (MapVector mapVector = MapVector.empty("parent", allocator)) {
      ComplexWriter writer = new ComplexWriterImpl("root", mapVector);
      MapWriter rootWriter = writer.rootAsMap();
      MapWriter mapWriter = rootWriter.map("map");

      for (int i = 0; i < COUNT; i++) {
        rootWriter.start();
        if (i % 2 == 0) {
          mapWriter.setPosition(i);
          mapWriter.start();
          mapWriter.bigInt("nested").writeBigInt(i);
          mapWriter.end();
        }
        rootWriter.end();
      }
      writer.setValueCount(COUNT);
      checkNullableMap(mapVector);
    }
  }

  private void checkNullableMap(MapVector mapVector) {
    MapReader rootReader = new SingleMapReaderImpl(mapVector).reader("root");
    for (int i = 0; i < COUNT; i++) {
      rootReader.setPosition(i);
      assertTrue("index is set: " + i, rootReader.isSet());
      FieldReader map = rootReader.reader("map");
      if (i % 2 == 0) {
        assertTrue("index is set: " + i, map.isSet());
        assertNotNull("index is set: " + i, map.readObject());
        assertEquals(i, map.reader("nested").readLong().longValue());
      } else {
        assertFalse("index is not set: " + i, map.isSet());
        assertNull("index is not set: " + i, map.readObject());
      }
    }
  }

  @Test
  public void testList() {
    MapVector parent = MapVector.empty("parent", allocator);
    ComplexWriter writer = new ComplexWriterImpl("root", parent);
    MapWriter rootWriter = writer.rootAsMap();

    rootWriter.start();
    rootWriter.bigInt("int").writeBigInt(0);
    rootWriter.list("list").startList();
    rootWriter.list("list").bigInt().writeBigInt(0);
    rootWriter.list("list").endList();
    rootWriter.end();

    rootWriter.start();
    rootWriter.bigInt("int").writeBigInt(1);
    rootWriter.end();

    writer.setValueCount(2);

    MapReader rootReader = new SingleMapReaderImpl(parent).reader("root");

    rootReader.setPosition(0);
    assertTrue("row 0 list is not set", rootReader.reader("list").isSet());
    assertEquals(Long.valueOf(0), rootReader.reader("list").reader().readLong());

    rootReader.setPosition(1);
    assertFalse("row 1 list is set", rootReader.reader("list").isSet());
  }

  @Test
  public void listScalarType() {
    ListVector listVector = ListVector.empty("list", allocator);
    listVector.allocateNew();
    UnionListWriter listWriter = new UnionListWriter(listVector);
    for (int i = 0; i < COUNT; i++) {
      listWriter.startList();
      for (int j = 0; j < i % 7; j++) {
        if (j % 2 == 0) {
          listWriter.writeInt(j);
        } else {
          IntHolder holder = new IntHolder();
          holder.value = j;
          listWriter.write(holder);
        }
      }
      listWriter.endList();
    }
    listWriter.setValueCount(COUNT);
    UnionListReader listReader = new UnionListReader(listVector);
    for (int i = 0; i < COUNT; i++) {
      listReader.setPosition(i);
      for (int j = 0; j < i % 7; j++) {
        listReader.next();
        assertEquals(j, listReader.reader().readInteger().intValue());
      }
    }
  }

  @Test
  public void listScalarTypeNullable() {
    ListVector listVector = ListVector.empty("list", allocator);
    listVector.allocateNew();
    UnionListWriter listWriter = new UnionListWriter(listVector);
    for (int i = 0; i < COUNT; i++) {
      if (i % 2 == 0) {
        listWriter.setPosition(i);
        listWriter.startList();
        for (int j = 0; j < i % 7; j++) {
          listWriter.writeInt(j);
        }
        listWriter.endList();
      }
    }
    listWriter.setValueCount(COUNT);
    UnionListReader listReader = new UnionListReader(listVector);
    for (int i = 0; i < COUNT; i++) {
      listReader.setPosition(i);
      if (i % 2 == 0) {
        assertTrue("index is set: " + i, listReader.isSet());
        assertEquals("correct length at: " + i, i % 7, ((List<?>) listReader.readObject()).size());
      } else {
        assertFalse("index is not set: " + i, listReader.isSet());
        assertNull("index is not set: " + i, listReader.readObject());
      }
    }
  }

  @Test
  public void listMapType() {
    ListVector listVector = ListVector.empty("list", allocator);
    listVector.allocateNew();
    UnionListWriter listWriter = new UnionListWriter(listVector);
    MapWriter mapWriter = listWriter.map();
    for (int i = 0; i < COUNT; i++) {
      listWriter.startList();
      for (int j = 0; j < i % 7; j++) {
        mapWriter.start();
        mapWriter.integer("int").writeInt(j);
        mapWriter.bigInt("bigInt").writeBigInt(j);
        mapWriter.end();
      }
      listWriter.endList();
    }
    listWriter.setValueCount(COUNT);
    UnionListReader listReader = new UnionListReader(listVector);
    for (int i = 0; i < COUNT; i++) {
      listReader.setPosition(i);
      for (int j = 0; j < i % 7; j++) {
        listReader.next();
        Assert.assertEquals("record: " + i, j, listReader.reader().reader("int").readInteger().intValue());
        Assert.assertEquals(j, listReader.reader().reader("bigInt").readLong().longValue());
      }
    }
  }

  @Test
  public void listListType() {
    try (ListVector listVector = ListVector.empty("list", allocator)) {
      listVector.allocateNew();
      UnionListWriter listWriter = new UnionListWriter(listVector);
      for (int i = 0; i < COUNT; i++) {
        listWriter.startList();
        for (int j = 0; j < i % 7; j++) {
          ListWriter innerListWriter = listWriter.list();
          innerListWriter.startList();
          for (int k = 0; k < i % 13; k++) {
            innerListWriter.integer().writeInt(k);
          }
          innerListWriter.endList();
        }
        listWriter.endList();
      }
      listWriter.setValueCount(COUNT);
      checkListOfLists(listVector);
    }
  }

  /**
   * This test is similar to {@link #listListType()} but we get the inner list writer once at the beginning
   */
  @Test
  public void listListType2() {
    try (ListVector listVector = ListVector.empty("list", allocator)) {
      listVector.allocateNew();
      UnionListWriter listWriter = new UnionListWriter(listVector);
      ListWriter innerListWriter = listWriter.list();

      for (int i = 0; i < COUNT; i++) {
        listWriter.startList();
        for (int j = 0; j < i % 7; j++) {
          innerListWriter.startList();
          for (int k = 0; k < i % 13; k++) {
            innerListWriter.integer().writeInt(k);
          }
          innerListWriter.endList();
        }
        listWriter.endList();
      }
      listWriter.setValueCount(COUNT);
      checkListOfLists(listVector);
    }
  }

  private void checkListOfLists(final ListVector listVector) {
    UnionListReader listReader = new UnionListReader(listVector);
    for (int i = 0; i < COUNT; i++) {
      listReader.setPosition(i);
      for (int j = 0; j < i % 7; j++) {
        listReader.next();
        FieldReader innerListReader = listReader.reader();
        for (int k = 0; k < i % 13; k++) {
          innerListReader.next();
          Assert.assertEquals("record: " + i, k, innerListReader.reader().readInteger().intValue());
        }
      }
    }
  }

  @Test
  public void unionListListType() {
    try (ListVector listVector = ListVector.empty("list", allocator)) {
      listVector.allocateNew();
      UnionListWriter listWriter = new UnionListWriter(listVector);
      for (int i = 0; i < COUNT; i++) {
        listWriter.startList();
        for (int j = 0; j < i % 7; j++) {
          ListWriter innerListWriter = listWriter.list();
          innerListWriter.startList();
          for (int k = 0; k < i % 13; k++) {
            if (k % 2 == 0) {
              innerListWriter.integer().writeInt(k);
            } else {
              innerListWriter.bigInt().writeBigInt(k);
            }
          }
          innerListWriter.endList();
        }
        listWriter.endList();
      }
      listWriter.setValueCount(COUNT);
      checkUnionList(listVector);
    }
  }

  /**
   * This test is similar to {@link #unionListListType()} but we get the inner list writer once at the beginning
   */
  @Test
  public void unionListListType2() {
    try (ListVector listVector = ListVector.empty("list", allocator)) {
      listVector.allocateNew();
      UnionListWriter listWriter = new UnionListWriter(listVector);
      ListWriter innerListWriter = listWriter.list();

      for (int i = 0; i < COUNT; i++) {
        listWriter.startList();
        for (int j = 0; j < i % 7; j++) {
          innerListWriter.startList();
          for (int k = 0; k < i % 13; k++) {
            if (k % 2 == 0) {
              innerListWriter.integer().writeInt(k);
            } else {
              innerListWriter.bigInt().writeBigInt(k);
            }
          }
          innerListWriter.endList();
        }
        listWriter.endList();
      }
      listWriter.setValueCount(COUNT);
      checkUnionList(listVector);
    }
  }

  private void checkUnionList(ListVector listVector) {
    UnionListReader listReader = new UnionListReader(listVector);
    for (int i = 0; i < COUNT; i++) {
      listReader.setPosition(i);
      for (int j = 0; j < i % 7; j++) {
        listReader.next();
        FieldReader innerListReader = listReader.reader();
        for (int k = 0; k < i % 13; k++) {
          innerListReader.next();
          if (k % 2 == 0) {
            Assert.assertEquals("record: " + i, k, innerListReader.reader().readInteger().intValue());
          } else {
            Assert.assertEquals("record: " + i, k, innerListReader.reader().readLong().longValue());
          }
        }
      }
    }
  }

  @Test
  public void simpleUnion() {
    UnionVector vector = new UnionVector("union", allocator, null);
    UnionWriter unionWriter = new UnionWriter(vector);
    unionWriter.allocate();
    for (int i = 0; i < COUNT; i++) {
      unionWriter.setPosition(i);
      if (i % 2 == 0) {
        unionWriter.writeInt(i);
      } else {
        unionWriter.writeFloat4((float) i);
      }
    }
    vector.setValueCount(COUNT);
    UnionReader unionReader = new UnionReader(vector);
    for (int i = 0; i < COUNT; i++) {
      unionReader.setPosition(i);
      if (i % 2 == 0) {
        Assert.assertEquals(i, i, unionReader.readInteger());
      } else {
        Assert.assertEquals((float) i, unionReader.readFloat(), 1e-12);
      }
    }
    vector.close();
  }

  @Test
  public void promotableWriter() {
    MapVector parent = MapVector.empty("parent", allocator);
    ComplexWriter writer = new ComplexWriterImpl("root", parent);
    MapWriter rootWriter = writer.rootAsMap();
    for (int i = 0; i < 100; i++) {
      BigIntWriter bigIntWriter = rootWriter.bigInt("a");
      bigIntWriter.setPosition(i);
      bigIntWriter.writeBigInt(i);
    }
    Field field = parent.getField().getChildren().get(0).getChildren().get(0);
    Assert.assertEquals("a", field.getName());
    Assert.assertEquals(Int.TYPE_TYPE, field.getType().getTypeID());
    Int intType = (Int) field.getType();

    Assert.assertEquals(64, intType.getBitWidth());
    Assert.assertTrue(intType.getIsSigned());
    for (int i = 100; i < 200; i++) {
      VarCharWriter varCharWriter = rootWriter.varChar("a");
      varCharWriter.setPosition(i);
      byte[] bytes = Integer.toString(i).getBytes();
      ArrowBuf tempBuf = allocator.buffer(bytes.length);
      tempBuf.setBytes(0, bytes);
      varCharWriter.writeVarChar(0, bytes.length, tempBuf);
    }
    field = parent.getField().getChildren().get(0).getChildren().get(0);
    Assert.assertEquals("a", field.getName());
    Assert.assertEquals(Union.TYPE_TYPE, field.getType().getTypeID());
    Assert.assertEquals(Int.TYPE_TYPE, field.getChildren().get(0).getType().getTypeID());
    Assert.assertEquals(Utf8.TYPE_TYPE, field.getChildren().get(1).getType().getTypeID());
    MapReader rootReader = new SingleMapReaderImpl(parent).reader("root");
    for (int i = 0; i < 100; i++) {
      rootReader.setPosition(i);
      FieldReader reader = rootReader.reader("a");
      Long value = reader.readLong();
      Assert.assertNotNull("index: " + i, value);
      Assert.assertEquals(i, value.intValue());
    }
    for (int i = 100; i < 200; i++) {
      rootReader.setPosition(i);
      FieldReader reader = rootReader.reader("a");
      Text value = reader.readText();
      Assert.assertEquals(Integer.toString(i), value.toString());
    }
  }

  /**
   * Even without writing to the writer, the union schema is created correctly
   */
  @Test
  public void promotableWriterSchema() {
    MapVector parent = MapVector.empty("parent", allocator);
    ComplexWriter writer = new ComplexWriterImpl("root", parent);
    MapWriter rootWriter = writer.rootAsMap();
    rootWriter.bigInt("a");
    rootWriter.varChar("a");

    Field field = parent.getField().getChildren().get(0).getChildren().get(0);
    Assert.assertEquals("a", field.getName());
    Assert.assertEquals(ArrowTypeID.Union, field.getType().getTypeID());

    Assert.assertEquals(ArrowTypeID.Int, field.getChildren().get(0).getType().getTypeID());
    Int intType = (Int) field.getChildren().get(0).getType();
    Assert.assertEquals(64, intType.getBitWidth());
    Assert.assertTrue(intType.getIsSigned());
    Assert.assertEquals(ArrowTypeID.Utf8, field.getChildren().get(1).getType().getTypeID());
  }

  private Set<String> getFieldNames(List<Field> fields) {
    Set<String> fieldNames = new HashSet<>();
    for (Field field : fields) {
      fieldNames.add(field.getName());
      if (!field.getChildren().isEmpty()) {
        for (String name : getFieldNames(field.getChildren())) {
          fieldNames.add(field.getName() + "::" + name);
        }
      }
    }
    return fieldNames;
  }

  @Test
  public void mapWriterMixedCaseFieldNames() {
    // test case-sensitive MapWriter
    MapVector parent = MapVector.empty("parent", allocator);
    ComplexWriter writer = new ComplexWriterImpl("rootCaseSensitive", parent, false, true);
    MapWriter rootWriterCaseSensitive = writer.rootAsMap();
    rootWriterCaseSensitive.bigInt("int_field");
    rootWriterCaseSensitive.bigInt("Int_Field");
    rootWriterCaseSensitive.float4("float_field");
    rootWriterCaseSensitive.float4("Float_Field");
    MapWriter mapFieldWriterCaseSensitive = rootWriterCaseSensitive.map("map_field");
    mapFieldWriterCaseSensitive.varChar("char_field");
    mapFieldWriterCaseSensitive.varChar("Char_Field");
    ListWriter listFieldWriterCaseSensitive = rootWriterCaseSensitive.list("list_field");
    MapWriter listMapFieldWriterCaseSensitive = listFieldWriterCaseSensitive.map();
    listMapFieldWriterCaseSensitive.bit("bit_field");
    listMapFieldWriterCaseSensitive.bit("Bit_Field");

    List<Field> fieldsCaseSensitive = parent.getField().getChildren().get(0).getChildren();
    Set<String> fieldNamesCaseSensitive = getFieldNames(fieldsCaseSensitive);
    Assert.assertEquals(11, fieldNamesCaseSensitive.size());
    Assert.assertTrue(fieldNamesCaseSensitive.contains("int_field"));
    Assert.assertTrue(fieldNamesCaseSensitive.contains("Int_Field"));
    Assert.assertTrue(fieldNamesCaseSensitive.contains("float_field"));
    Assert.assertTrue(fieldNamesCaseSensitive.contains("Float_Field"));
    Assert.assertTrue(fieldNamesCaseSensitive.contains("map_field"));
    Assert.assertTrue(fieldNamesCaseSensitive.contains("map_field::char_field"));
    Assert.assertTrue(fieldNamesCaseSensitive.contains("map_field::Char_Field"));
    Assert.assertTrue(fieldNamesCaseSensitive.contains("list_field"));
    Assert.assertTrue(fieldNamesCaseSensitive.contains("list_field::$data$"));
    Assert.assertTrue(fieldNamesCaseSensitive.contains("list_field::$data$::bit_field"));
    Assert.assertTrue(fieldNamesCaseSensitive.contains("list_field::$data$::Bit_Field"));

    // test case-insensitive MapWriter
    ComplexWriter writerCaseInsensitive = new ComplexWriterImpl("rootCaseInsensitive", parent, false, false);
    MapWriter rootWriterCaseInsensitive = writerCaseInsensitive.rootAsMap();

    rootWriterCaseInsensitive.bigInt("int_field");
    rootWriterCaseInsensitive.bigInt("Int_Field");
    rootWriterCaseInsensitive.float4("float_field");
    rootWriterCaseInsensitive.float4("Float_Field");
    MapWriter mapFieldWriterCaseInsensitive = rootWriterCaseInsensitive.map("map_field");
    mapFieldWriterCaseInsensitive.varChar("char_field");
    mapFieldWriterCaseInsensitive.varChar("Char_Field");
    ListWriter listFieldWriterCaseInsensitive = rootWriterCaseInsensitive.list("list_field");
    MapWriter listMapFieldWriterCaseInsensitive = listFieldWriterCaseInsensitive.map();
    listMapFieldWriterCaseInsensitive.bit("bit_field");
    listMapFieldWriterCaseInsensitive.bit("Bit_Field");

    List<Field> fieldsCaseInsensitive = parent.getField().getChildren().get(1).getChildren();
    Set<String> fieldNamesCaseInsensitive = getFieldNames(fieldsCaseInsensitive);
    Assert.assertEquals(7, fieldNamesCaseInsensitive.size());
    Assert.assertTrue(fieldNamesCaseInsensitive.contains("int_field"));
    Assert.assertTrue(fieldNamesCaseInsensitive.contains("float_field"));
    Assert.assertTrue(fieldNamesCaseInsensitive.contains("map_field"));
    Assert.assertTrue(fieldNamesCaseInsensitive.contains("map_field::char_field"));
    Assert.assertTrue(fieldNamesCaseSensitive.contains("list_field"));
    Assert.assertTrue(fieldNamesCaseSensitive.contains("list_field::$data$"));
    Assert.assertTrue(fieldNamesCaseSensitive.contains("list_field::$data$::bit_field"));
  }

  @Test
  public void timeStampSecWriter() throws Exception {
    // test values
    final long expectedSecs = 981173106L;
    final LocalDateTime expectedSecDateTime = new LocalDateTime(2001, 2, 3, 4, 5, 6, 0);

    // write
    MapVector parent = new MapVector("parent", allocator, null);
    ComplexWriter writer = new ComplexWriterImpl("root", parent);
    MapWriter rootWriter = writer.rootAsMap();

    {
      TimeStampSecWriter timeStampSecWriter = rootWriter.timeStampSec("sec");
      timeStampSecWriter.setPosition(0);
      timeStampSecWriter.writeTimeStampSec(expectedSecs);
    }
    {
      TimeStampSecTZWriter timeStampSecTZWriter = rootWriter.timeStampSecTZ("secTZ", "UTC");
      timeStampSecTZWriter.setPosition(1);
      timeStampSecTZWriter.writeTimeStampSecTZ(expectedSecs);
    }
    // schema
    List<Field> children = parent.getField().getChildren().get(0).getChildren();
    checkTimestampField(children.get(0), "sec");
    checkTimestampTZField(children.get(1), "secTZ", "UTC");

    // read
    MapReader rootReader = new SingleMapReaderImpl(parent).reader("root");
    {
      FieldReader secReader = rootReader.reader("sec");
      secReader.setPosition(0);
      LocalDateTime secDateTime = secReader.readLocalDateTime();
      Assert.assertEquals(expectedSecDateTime, secDateTime);
      long secLong = secReader.readLong();
      Assert.assertEquals(expectedSecs, secLong);
    }
    {
      FieldReader secTZReader = rootReader.reader("secTZ");
      secTZReader.setPosition(1);
      long secTZLong = secTZReader.readLong();
      Assert.assertEquals(expectedSecs, secTZLong);
    }
  }

  @Test
  public void timeStampMilliWriters() throws Exception {
    // test values
    final long expectedMillis = 981173106123L;
    final LocalDateTime expectedMilliDateTime = new LocalDateTime(2001, 2, 3, 4, 5, 6, 123);

    // write
    MapVector parent = MapVector.empty("parent", allocator);
    ComplexWriter writer = new ComplexWriterImpl("root", parent);
    MapWriter rootWriter = writer.rootAsMap();
    {
      TimeStampMilliWriter timeStampWriter = rootWriter.timeStampMilli("milli");
      timeStampWriter.setPosition(0);
      timeStampWriter.writeTimeStampMilli(expectedMillis);
    }
    String tz = DateUtility.getTimeZone(10);
    {
      TimeStampMilliTZWriter timeStampTZWriter = rootWriter.timeStampMilliTZ("milliTZ", tz);
      timeStampTZWriter.setPosition(0);
      timeStampTZWriter.writeTimeStampMilliTZ(expectedMillis);
    }
    // schema
    List<Field> children = parent.getField().getChildren().get(0).getChildren();
    checkTimestampField(children.get(0), "milli");
    checkTimestampTZField(children.get(1), "milliTZ", tz);

    // read
    MapReader rootReader = new SingleMapReaderImpl(parent).reader("root");

    {
      FieldReader milliReader = rootReader.reader("milli");
      milliReader.setPosition(0);
      LocalDateTime milliDateTime = milliReader.readLocalDateTime();
      Assert.assertEquals(expectedMilliDateTime, milliDateTime);
      long milliLong = milliReader.readLong();
      Assert.assertEquals(expectedMillis, milliLong);
    }
    {
      FieldReader milliTZReader = rootReader.reader("milliTZ");
      milliTZReader.setPosition(0);
      long milliTZLong = milliTZReader.readLong();
      Assert.assertEquals(expectedMillis, milliTZLong);
    }

  }

  private void checkTimestampField(Field field, String name) {
    Assert.assertEquals(name, field.getName());
    Assert.assertEquals(ArrowType.Timestamp.TYPE_TYPE, field.getType().getTypeID());
  }

  private void checkTimestampTZField(Field field, String name, String tz) {
    checkTimestampField(field, name);
    Assert.assertEquals(tz, ((Timestamp) field.getType()).getTimezone());
  }

  @Test
  public void timeStampMicroWriters() throws Exception {
    // test values
    final long expectedMicros = 981173106123456L;
    final LocalDateTime expectedMicroDateTime = new LocalDateTime(2001, 2, 3, 4, 5, 6, 123);

    // write
    MapVector parent = new MapVector("parent", allocator, null);
    ComplexWriter writer = new ComplexWriterImpl("root", parent);
    MapWriter rootWriter = writer.rootAsMap();

    {
      TimeStampMicroWriter timeStampMicroWriter = rootWriter.timeStampMicro("micro");
      timeStampMicroWriter.setPosition(0);
      timeStampMicroWriter.writeTimeStampMicro(expectedMicros);
    }
    String tz = DateUtility.getTimeZone(5);
    {
      TimeStampMicroTZWriter timeStampMicroWriter = rootWriter.timeStampMicroTZ("microTZ", tz);
      timeStampMicroWriter.setPosition(1);
      timeStampMicroWriter.writeTimeStampMicroTZ(expectedMicros);
    }

    // schema
    List<Field> children = parent.getField().getChildren().get(0).getChildren();
    checkTimestampField(children.get(0), "micro");
    checkTimestampTZField(children.get(1), "microTZ", tz);

    // read
    MapReader rootReader = new SingleMapReaderImpl(parent).reader("root");
    {
      FieldReader microReader = rootReader.reader("micro");
      microReader.setPosition(0);
      LocalDateTime microDateTime = microReader.readLocalDateTime();
      Assert.assertEquals(expectedMicroDateTime, microDateTime);
      long microLong = microReader.readLong();
      Assert.assertEquals(expectedMicros, microLong);
    }
    {
      FieldReader microReader = rootReader.reader("microTZ");
      microReader.setPosition(1);
      long microLong = microReader.readLong();
      Assert.assertEquals(expectedMicros, microLong);
    }

  }

  @Test
  public void timeStampNanoWriters() throws Exception {
    // test values
    final long expectedNanos = 981173106123456789L;
    final LocalDateTime expectedNanoDateTime = new LocalDateTime(2001, 2, 3, 4, 5, 6, 123);

    // write
    MapVector parent = new MapVector("parent", allocator, null);
    ComplexWriter writer = new ComplexWriterImpl("root", parent);
    MapWriter rootWriter = writer.rootAsMap();

    {
      TimeStampNanoWriter timeStampNanoWriter = rootWriter.timeStampNano("nano");
      timeStampNanoWriter.setPosition(0);
      timeStampNanoWriter.writeTimeStampNano(expectedNanos);
    }
    String tz = DateUtility.getTimeZone(3);
    {
      TimeStampNanoTZWriter timeStampNanoWriter = rootWriter.timeStampNanoTZ("nanoTZ", tz);
      timeStampNanoWriter.setPosition(0);
      timeStampNanoWriter.writeTimeStampNanoTZ(expectedNanos);
    }
    // schema
    List<Field> children = parent.getField().getChildren().get(0).getChildren();
    checkTimestampField(children.get(0), "nano");
    checkTimestampTZField(children.get(1), "nanoTZ", tz);
    // read
    MapReader rootReader = new SingleMapReaderImpl(parent).reader("root");

    {
      FieldReader nanoReader = rootReader.reader("nano");
      nanoReader.setPosition(0);
      LocalDateTime nanoDateTime = nanoReader.readLocalDateTime();
      Assert.assertEquals(expectedNanoDateTime, nanoDateTime);
      long nanoLong = nanoReader.readLong();
      Assert.assertEquals(expectedNanos, nanoLong);
    }
    {
      FieldReader nanoReader = rootReader.reader("nanoTZ");
      nanoReader.setPosition(0);
      long nanoLong = nanoReader.readLong();
      Assert.assertEquals(expectedNanos, nanoLong);
      NullableTimeStampNanoTZHolder h = new NullableTimeStampNanoTZHolder();
      nanoReader.read(h);
      Assert.assertEquals(expectedNanos, h.value);
    }
  }

  @Test
  public void complexCopierWithList() {
    MapVector parent = MapVector.empty("parent", allocator);
    ComplexWriter writer = new ComplexWriterImpl("root", parent);
    MapWriter rootWriter = writer.rootAsMap();
    ListWriter listWriter = rootWriter.list("list");
    MapWriter innerMapWriter = listWriter.map();
    IntWriter outerIntWriter = listWriter.integer();
    rootWriter.start();
    listWriter.startList();
    outerIntWriter.writeInt(1);
    outerIntWriter.writeInt(2);
    innerMapWriter.start();
    IntWriter intWriter = innerMapWriter.integer("a");
    intWriter.writeInt(1);
    innerMapWriter.end();
    innerMapWriter.start();
    intWriter = innerMapWriter.integer("a");
    intWriter.writeInt(2);
    innerMapWriter.end();
    listWriter.endList();
    rootWriter.end();
    writer.setValueCount(1);

    NullableMapVector mapVector = (NullableMapVector) parent.getChild("root");
    TransferPair tp = mapVector.getTransferPair(allocator);
    tp.splitAndTransfer(0, 1);
    MapVector toMapVector = (MapVector) tp.getTo();
    JsonStringHashMap<?, ?> toMapValue = (JsonStringHashMap<?, ?>) toMapVector.getObject(0);
    JsonStringArrayList<?> object = (JsonStringArrayList<?>) toMapValue.get("list");
    assertEquals(1, object.get(0));
    assertEquals(2, object.get(1));
    JsonStringHashMap<?, ?> innerMap = (JsonStringHashMap<?, ?>) object.get(2);
    assertEquals(1, innerMap.get("a"));
    innerMap = (JsonStringHashMap<?, ?>) object.get(3);
    assertEquals(2, innerMap.get("a"));
  }

  @Test
  public void testSingleMapWriter1() {
    /* initialize a SingleMapWriter with empty MapVector and then lazily
     * create all vectors with expected initialCapacity.
     */
    MapVector parent = MapVector.empty("parent", allocator);
    SingleMapWriter singleMapWriter = new SingleMapWriter(parent);

    int initialCapacity = 1024;
    singleMapWriter.setInitialCapacity(initialCapacity);

    IntWriter intWriter = singleMapWriter.integer("intField");
    BigIntWriter bigIntWriter = singleMapWriter.bigInt("bigIntField");
    Float4Writer float4Writer = singleMapWriter.float4("float4Field");
    Float8Writer float8Writer = singleMapWriter.float8("float8Field");
    ListWriter listWriter = singleMapWriter.list("listField");

    int intValue = 100;
    long bigIntValue = 10000;
    float float4Value = 100.5f;
    double float8Value = 100.375;

    for (int i = 0; i < initialCapacity; i++) {
      singleMapWriter.start();

      intWriter.writeInt(intValue + i);
      bigIntWriter.writeBigInt(bigIntValue + (long)i);
      float4Writer.writeFloat4(float4Value + (float)i);
      float8Writer.writeFloat8(float8Value + (double)i);

      listWriter.setPosition(i);
      listWriter.startList();
      listWriter.integer().writeInt(intValue + i);
      listWriter.integer().writeInt(intValue + i + 1);
      listWriter.integer().writeInt(intValue + i + 2);
      listWriter.integer().writeInt(intValue + i + 3);
      listWriter.endList();

      singleMapWriter.end();
    }

    NullableIntVector intVector = (NullableIntVector)parent.getChild("intField");
    NullableBigIntVector bigIntVector = (NullableBigIntVector)parent.getChild("bigIntField");
    NullableFloat4Vector float4Vector = (NullableFloat4Vector)parent.getChild("float4Field");
    NullableFloat8Vector float8Vector = (NullableFloat8Vector)parent.getChild("float8Field");

    assertEquals(initialCapacity, singleMapWriter.getValueCapacity());
    assertEquals(initialCapacity, intVector.getValueCapacity());
    assertEquals(initialCapacity, bigIntVector.getValueCapacity());
    assertEquals(initialCapacity, float4Vector.getValueCapacity());
    assertEquals(initialCapacity, float8Vector.getValueCapacity());

    MapReader singleMapReader = new SingleMapReaderImpl(parent);

    IntReader intReader = singleMapReader.reader("intField");
    BigIntReader bigIntReader = singleMapReader.reader("bigIntField");
    Float4Reader float4Reader = singleMapReader.reader("float4Field");
    Float8Reader float8Reader = singleMapReader.reader("float8Field");
    UnionListReader listReader = (UnionListReader)singleMapReader.reader("listField");

    for (int i = 0; i < initialCapacity; i++) {
      intReader.setPosition(i);
      bigIntReader.setPosition(i);
      float4Reader.setPosition(i);
      float8Reader.setPosition(i);
      listReader.setPosition(i);

      assertEquals(intValue + i, intReader.readInteger().intValue());
      assertEquals(bigIntValue + (long)i, bigIntReader.readLong().longValue());
      assertEquals(float4Value + (float)i, float4Reader.readFloat().floatValue(), 0);
      assertEquals(float8Value + (double)i, float8Reader.readDouble().doubleValue(), 0);

      for (int j = 0; j < 4; j++) {
        listReader.next();
        assertEquals(intValue + i + j, listReader.reader().readInteger().intValue());
      }
    }
  }
}
