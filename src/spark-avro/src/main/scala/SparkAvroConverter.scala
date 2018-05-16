/*
 * Copyright 2014 Databricks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
  * *** ATTRIBUTION NOTICE ***
  *
  * The class IncompatibleSchemaException and method createConverterToSql in this package are copied verbatim from
  * https://github.com/databricks/spark-avro/blob/branch-4.0/src/main/scala/com/databricks/spark/avro/SchemaConverters.scala.
  *
  * The method getNewRecordNamespace in this package is copied verbatim from
  * https://github.com/databricks/spark-avro/blob/branch-4.0/src/main/scala/com/databricks/spark/avro/AvroOutputWriter.scala.
  *
  * The method createConverterToAvro in this package is a derivative of createConverterToAvro from
  * https://github.com/databricks/spark-avro/blob/branch-4.0/src/main/scala/com/databricks/spark/avro/AvroOutputWriter.scala
  * where Spark SQL ArrayType objects are converted to Avro GenericData.Array objects, rather than Scala Array objects.
  */
package jep.spark.avro

import java.nio.ByteBuffer
import java.sql.{Date, Timestamp}
import java.util.HashMap

import com.databricks.spark.avro.SchemaConverters
import org.apache.avro.{Schema, SchemaBuilder}
import org.apache.avro.Schema.Type._
import org.apache.avro.generic.GenericData.Record
import org.apache.avro.generic.{GenericData, GenericFixed, GenericRecord}
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.expressions.GenericRow
import org.apache.spark.sql.types._

import scala.collection.JavaConverters._
import scala.collection.immutable.Map
import scala.reflect.runtime.universe._

object SparkAvroConverter extends Serializable {

  private class IncompatibleSchemaException(msg: String, ex: Throwable = null) extends Exception(msg, ex)

  private def createConverterToSql(sourceAvroSchema: Schema, targetSqlType: DataType): AnyRef => AnyRef = {
    def createConverter(avroSchema: Schema, sqlType: DataType, path: List[String]): AnyRef => AnyRef = {
      val avroType = avroSchema.getType
      (sqlType, avroType) match {
        // Avro strings are in Utf8, so we have to call toString on them
        case (StringType, STRING) | (StringType, ENUM) =>
          (item: AnyRef) => if (item == null) null else item.toString
        // Byte arrays are reused by avro, so we have to make a copy of them.
        case (IntegerType, INT) | (BooleanType, BOOLEAN) | (DoubleType, DOUBLE) |
             (FloatType, FLOAT) | (LongType, LONG) =>
          identity
        case (BinaryType, FIXED) =>
          (item: AnyRef) =>
            if (item == null) {
              null
            } else {
              item.asInstanceOf[GenericFixed].bytes().clone()
            }
        case (BinaryType, BYTES) =>
          (item: AnyRef) =>
            if (item == null) {
              null
            } else {
              val byteBuffer = item.asInstanceOf[ByteBuffer]
              val bytes = new Array[Byte](byteBuffer.remaining)
              byteBuffer.get(bytes)
              bytes
            }

        case (struct: StructType, RECORD) =>
          val length = struct.fields.length
          val converters = new Array[AnyRef => AnyRef](length)
          val avroFieldIndexes = new Array[Int](length)
          var i = 0
          while (i < length) {
            val sqlField = struct.fields(i)
            val avroField = avroSchema.getField(sqlField.name)
            if (avroField != null) {
              val converter = createConverter(avroField.schema(), sqlField.dataType,
                path :+ sqlField.name)
              converters(i) = converter
              avroFieldIndexes(i) = avroField.pos()
            } else if (!sqlField.nullable) {
              throw new IncompatibleSchemaException(
                s"Cannot find non-nullable field ${sqlField.name} at path ${path.mkString(".")} " +
                  "in Avro schema\n" +
                  s"Source Avro schema: $sourceAvroSchema.\n" +
                  s"Target Catalyst type: $targetSqlType")
            }
            i += 1
          }

          (item: AnyRef) => {
            if (item == null) {
              null
            } else {
              val record = item.asInstanceOf[GenericRecord]

              val result = new Array[Any](length)
              var i = 0
              while (i < converters.length) {
                if (converters(i) != null) {
                  val converter = converters(i)
                  result(i) = converter(record.get(avroFieldIndexes(i)))
                }
                i += 1
              }
              new GenericRow(result)
            }
          }
        case (arrayType: ArrayType, ARRAY) =>
          val elementConverter = createConverter(avroSchema.getElementType, arrayType.elementType,
            path)
          val allowsNull = arrayType.containsNull
          (item: AnyRef) => {
            if (item == null) {
              null
            } else {
              item.asInstanceOf[java.lang.Iterable[AnyRef]].asScala.map { element =>
                if (element == null && !allowsNull) {
                  throw new RuntimeException(s"Array value at path ${path.mkString(".")} is not " +
                    "allowed to be null")
                } else {
                  elementConverter(element)
                }
              }
            }
          }
        case (mapType: MapType, MAP) if mapType.keyType == StringType =>
          val valueConverter = createConverter(avroSchema.getValueType, mapType.valueType, path)
          val allowsNull = mapType.valueContainsNull
          (item: AnyRef) => {
            if (item == null) {
              null
            } else {
              item.asInstanceOf[java.util.Map[AnyRef, AnyRef]].asScala.map { x =>
                if (x._2 == null && !allowsNull) {
                  throw new RuntimeException(s"Map value at path ${path.mkString(".")} is not " +
                    "allowed to be null")
                } else {
                  (x._1.toString, valueConverter(x._2))
                }
              }.toMap
            }
          }
        case (sqlType, UNION) =>
          if (avroSchema.getTypes.asScala.exists(_.getType == NULL)) {
            val remainingUnionTypes = avroSchema.getTypes.asScala.filterNot(_.getType == NULL)
            if (remainingUnionTypes.size == 1) {
              createConverter(remainingUnionTypes.head, sqlType, path)
            } else {
              createConverter(Schema.createUnion(remainingUnionTypes.asJava), sqlType, path)
            }
          } else avroSchema.getTypes.asScala.map(_.getType) match {
            case Seq(t1) => createConverter(avroSchema.getTypes.get(0), sqlType, path)
            case Seq(a, b) if Set(a, b) == Set(INT, LONG) && sqlType == LongType =>
              (item: AnyRef) => {
                item match {
                  case null => null
                  case l: java.lang.Long => l
                  case i: java.lang.Integer => new java.lang.Long(i.longValue())
                }
              }
            case Seq(a, b) if Set(a, b) == Set(FLOAT, DOUBLE) && sqlType == DoubleType =>
              (item: AnyRef) => {
                item match {
                  case null => null
                  case d: java.lang.Double => d
                  case f: java.lang.Float => new java.lang.Double(f.doubleValue())
                }
              }
            case other =>
              sqlType match {
                case t: StructType if t.fields.length == avroSchema.getTypes.size =>
                  val fieldConverters = t.fields.zip(avroSchema.getTypes.asScala).map {
                    case (field, schema) =>
                      createConverter(schema, field.dataType, path :+ field.name)
                  }

                  (item: AnyRef) => if (item == null) {
                    null
                  } else {
                    val i = GenericData.get().resolveUnion(avroSchema, item)
                    val converted = new Array[Any](fieldConverters.length)
                    converted(i) = fieldConverters(i)(item)
                    new GenericRow(converted)
                  }
                case _ => throw new IncompatibleSchemaException(
                  s"Cannot convert Avro schema to catalyst type because schema at path " +
                    s"${path.mkString(".")} is not compatible " +
                    s"(avroType = $other, sqlType = $sqlType). \n" +
                    s"Source Avro schema: $sourceAvroSchema.\n" +
                    s"Target Catalyst type: $targetSqlType")
              }
          }
        case (left, right) =>
          throw new IncompatibleSchemaException(
            s"Cannot convert Avro schema to catalyst type because schema at path " +
              s"${path.mkString(".")} is not compatible (avroType = $left, sqlType = $right). \n" +
              s"Source Avro schema: $sourceAvroSchema.\n" +
              s"Target Catalyst type: $targetSqlType")
      }
    }
    createConverter(sourceAvroSchema, targetSqlType, List.empty[String])
  }

  private def getNewRecordNamespace(elementDataType: DataType, currentRecordNamespace: String, elementName: String): String = {
    elementDataType match {
      case StructType(_) => s"$currentRecordNamespace.$elementName"
      case _ => currentRecordNamespace
    }
  }

  private def createConverterToAvro(dataType: DataType, structName: String, recordNamespace: String): (Any) => Any = {
    dataType match {
      case BinaryType => (item: Any) => item match {
        case null => null
        case bytes: Array[Byte] => ByteBuffer.wrap(bytes)
      }
      case ByteType | ShortType | IntegerType | LongType |
           FloatType | DoubleType | StringType | BooleanType => identity
      case _: DecimalType => (item: Any) => if (item == null) null else item.toString
      case TimestampType => (item: Any) =>
        if (item == null) null else item.asInstanceOf[Timestamp].getTime
      case DateType => (item: Any) =>
        if (item == null) null else item.asInstanceOf[Date].getTime
      case ArrayType(elementType, _) =>
        val elementConverter = createConverterToAvro(
          elementType,
          structName,
          getNewRecordNamespace(elementType, recordNamespace, structName))
        (item: Any) => {
          if (item == null) {
            null
          } else {
            createGenericArray(item, elementConverter)
          }
        }
      case MapType(StringType, valueType, _) =>
        val valueConverter = createConverterToAvro(
          valueType,
          structName,
          getNewRecordNamespace(valueType, recordNamespace, structName))
        (item: Any) => {
          if (item == null) {
            null
          } else {
            val javaMap = new HashMap[String, Any]()
            item.asInstanceOf[Map[String, Any]].foreach { case (key, value) =>
              javaMap.put(key, valueConverter(value))
            }
            javaMap
          }
        }
      case structType: StructType =>
        val schema: Schema = getSchema(structType, structName, recordNamespace)
        val fieldConverters = structType.fields.map(field =>
          createConverterToAvro(
            field.dataType,
            field.name,
            getNewRecordNamespace(field.dataType, recordNamespace, field.name)))
        (item: Any) => {
          if (item == null) {
            null
          } else {
            val record = new Record(schema)
            val convertersIterator = fieldConverters.iterator
            val fieldNamesIterator = dataType.asInstanceOf[StructType].fieldNames.iterator
            val rowIterator = item.asInstanceOf[Row].toSeq.iterator

            while (convertersIterator.hasNext) {
              val converter = convertersIterator.next()
              record.put(fieldNamesIterator.next(), converter(rowIterator.next()))
            }
            record.asInstanceOf[GenericRecord]
          }
        }
    }
  }

  def rowToAvro(row: Row, schemaRegistryService: SchemaRegistryService, topic: String, name: String, namespace: String): Array[Byte] = {
    val converter = createConverterToAvro(row.schema, name, namespace)
    val genericRecord = converter(row).asInstanceOf[GenericRecord]
    schemaRegistryService.serialize(topic, genericRecord)
  }

  def avroToRow(record: Array[Byte], schemaRegistryService: SchemaRegistryService, topic: String, targetStruct: StructType): Row = {
    val genericRecord = schemaRegistryService.deserialize(topic, record).asInstanceOf[GenericRecord]
    val converter = createConverterToSql(genericRecord.getSchema, targetStruct)
    converter(genericRecord).asInstanceOf[Row]
  }

  var schemaStore = collection.mutable.Map[String, Schema]()

  private def getSchema(structType: StructType, name: String, namespace: String): Schema = {
    if (schemaStore.contains(name)) {
      schemaStore(name)
    }
    else {
      val builder = SchemaBuilder.record(name).namespace(namespace)
      val schema = SchemaConverters.convertStructToAvro(structType, builder, namespace)
      schemaStore += (name -> schema)
      schema
    }
  }

  private def createGenericArray[T](item: Any, elementConverter: Any => Any)(implicit tag: TypeTag[T]): org.apache.avro.generic.GenericData.Array[T] = {
    val seq = item.asInstanceOf[Seq[Any]]
    import scala.collection.JavaConversions._
    val collection: java.util.Collection[T] = seq.map(element => elementConverter(element).asInstanceOf[T]).toSeq
    val avroType = typeOf[T].toString.toLowerCase match {
      case "byte" => "bytes"
      case "nothing" => "null"
      case other => other
    }
    val arraySchema = new Schema.Parser().parse("{\"type\":\"array\",\"items\":\"" + avroType + "\"}")
    new org.apache.avro.generic.GenericData.Array(arraySchema, collection)
  }

}
