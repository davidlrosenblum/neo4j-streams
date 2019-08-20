package streams.service.errors

import org.apache.commons.lang3.exception.ExceptionUtils
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.record.RecordBatch
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.neo4j.util.VisibleForTesting
import java.util.*

class KafkaDLQService(private val producer: Producer<ByteArray, ByteArray>?, private val errorConfig: ErrorConfig, private val log: (String,Exception?)->Unit): ErrorService() {

    constructor(config: Properties, errorConfig: ErrorConfig,
                log: (String, Exception?) -> Unit) : this(producer(errorConfig, config), errorConfig, log)

    companion object {
        private fun producer(errorConfig: ErrorConfig, config: Properties) =
                errorConfig.dlqTopic?.let {
                    val props = Properties()
                    props.putAll(config)
                    props[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = ByteArraySerializer::class.java.name
                    props[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = ByteArraySerializer::class.java.name
                    KafkaProducer<ByteArray,ByteArray>(props)
                }
    }

    override fun report(errorDatas: List<ErrorData>) {
        if (errorConfig.fail) throw ProcessingError(errorDatas)
        if (errorConfig.log) {
            if (errorConfig.logMessages) {
                errorDatas.forEach{log(it.toLogString(),it.exception)}
            } else {
                errorDatas.map { it.exception }.distinct().forEach{log("Error processing ${errorDatas.size} messages",it)}
            }
        }
        if (errorConfig.dlqTopic != null && producer != null) {
            errorDatas.forEach { dlqData ->
                try {
                    val producerRecord = if (dlqData.timestamp == RecordBatch.NO_TIMESTAMP) {
                        ProducerRecord(errorConfig.dlqTopic, null, dlqData.key, dlqData.value)
                    } else {
                        ProducerRecord(errorConfig.dlqTopic, null, dlqData.timestamp, dlqData.key, dlqData.value)
                    }
                    if (errorConfig.dlqHeaders) {
                        val producerHeader = producerRecord.headers()
                        populateContextHeaders(dlqData).forEach { (key, value) -> producerHeader.add(key, value) }
                    }
                    producer.send(producerRecord)
                } catch (e: Exception) {
                    log("Error writing to DLQ $e :${dlqData.toLogString()}",e) // todo only the first or all
                }
            }
        }
    }

    @VisibleForTesting
    fun populateContextHeaders(errorData: ErrorData): Map<String, ByteArray> {
        fun prefix(suffix: String) = errorConfig.dlqHeaderPrefix + suffix

        val headers = mutableMapOf(
        prefix("topic") to errorData.originalTopic.toByteArray(),
        prefix("partition") to errorData.partition.toByteArray(),
        prefix("offset") to errorData.offset.toByteArray())

        if (errorData.executingClass != null) {
            headers[prefix("class.name")] = errorData.executingClass.name.toByteArray()
        }
        if (errorData.exception != null) {
            headers[prefix("exception.class.name")] = errorData.exception.javaClass.name.toByteArray()
            if (errorData.exception.message != null) {
                headers[prefix("exception.message")] = errorData.exception.message.toString().toByteArray()
            }
            headers[prefix("exception.stacktrace")] = ExceptionUtils.getStackTrace(errorData.exception).toByteArray()
        }
        return headers
    }


    override fun close() {
        this.producer?.close()
    }

}