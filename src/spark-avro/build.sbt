name := "spark-avro"

version := "0.1"

scalaVersion := "2.11.8"

resolvers ++= Seq(
  "confluent" at "http://packages.confluent.io/maven/"
)

libraryDependencies ++= Seq(
  "org.apache.spark" % "spark-hive_2.11" % "2.2.0",
  "org.apache.avro" % "avro" % "1.7.7",
  "org.apache.kafka" % "kafka_2.11" % "0.10.2.1",
  "io.confluent" % "kafka-avro-serializer" % "3.3.1",
  "com.databricks" % "spark-avro_2.11" % "4.0.0"
)