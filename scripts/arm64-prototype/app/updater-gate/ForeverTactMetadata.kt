package app.gamenative

/** Shared, bounded wire models for the on-device TACT metadata producer. */
internal object ForeverTactMetadata {
    data class ObjectReference(
        val role: String,
        val contentKey: String,
        val encodingKey: String,
        val encodedSize: Long,
        val decodedSize: Long?,
    )

    data class ContentReference(val role: String, val contentKey: String, val decodedSize: Long? = null)

    data class BuildConfig(
        val fingerprint: ForeverClientUpdateCheck.Fingerprint,
        val metadataObjects: Map<String, ObjectReference>,
        val runtimeContents: List<ContentReference>,
        val rawSha256: String,
    )

    data class CdnConfig(val archives: List<String>, val fileIndex: String?, val rawSha256: String)

    data class InstallRecord(val path: String, val contentKey: String, val decodedSize: Long)
    data class DownloadRecord(val encodingKey: String, val encodedSize: Long, val priority: Int)
    data class SizeRecord(val encodingKeyPrefix: String, val encodedSize: Long)
    data class ContentMapping(val contentKey: String, val encodingKey: String, val decodedSize: Long)
    data class EncodingRecord(val encodingKey: String, val encodedSize: Long)
    data class SourceRecord(
        val encodingKey: String,
        val encodedSize: Long,
        val sourceKind: String,
        val sourceHash: String,
        val sourceOffset: Long,
    )

    data class SelectionStats(val totalEntries: Long, val selectedEntries: Long, val selectedEncodedBytes: Long)
    data class EncodingStats(val contentMappings: Long, val encodingEntries: Long, val trailingBytes: Long)
    data class IndexStats(val entries: Long, val sourceKind: String)
}
