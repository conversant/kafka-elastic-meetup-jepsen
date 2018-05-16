package jep.spark.avro

import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import io.confluent.kafka.serializers.{KafkaAvroDeserializer, KafkaAvroDeserializerConfig, KafkaAvroSerializer}

import scala.collection.JavaConverters._

class SchemaRegistryService(schemaRegistryUrl: String, cacheCapacity: Int) extends Serializable {

  private lazy val schemaRegistry = {
    val urls = List(schemaRegistryUrl).asJava
    new CachedSchemaRegistryClient(urls, cacheCapacity)
  }

  private lazy val config = {
    val map = new java.util.HashMap[String, String]()
    map.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, "false")
    map.put("schema.registry.url", schemaRegistryUrl)
    map
  }

  private lazy val avroDeserializer = new KafkaAvroDeserializer(schemaRegistry, config)
  private lazy val avroSerializer = new KafkaAvroSerializer(schemaRegistry, config)

  def serialize(topic: String, record: AnyRef): Array[Byte] = avroSerializer.serialize(topic, record)
  def deserialize(topic: String, record: Array[Byte] ) = avroDeserializer.deserialize(topic, record)

}